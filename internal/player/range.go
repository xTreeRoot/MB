package player

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"mygo-music/internal/music"
)

const mediaBlock = 256 << 10

var errMediaInterrupted = errors.New("media connection interrupted")
var errMediaChanged = errors.New("media representation changed")

type mediaHTTPError int

func (status mediaHTTPError) Error() string {
	return fmt.Sprintf("media request failed (HTTP %d)", status)
}

func retryMedia(err error) bool {
	var status mediaHTTPError
	return errors.Is(err, errMediaInterrupted) || errors.As(err, &status) && status >= 500 && status < 600
}

func refreshMedia(err error) bool {
	var status mediaHTTPError
	return retryMedia(err) || errors.Is(err, errMediaChanged) || errors.As(err, &status) && (status == 401 || status == 410)
}

type byteRanges struct {
	mu        sync.Mutex
	ctx       context.Context
	url       string
	headers   map[string]string
	size      int64
	tag       string
	file      *os.File
	onDisk    map[int64]bool
	blocks    map[int64][]byte
	order     []int64
	covered   atomic.Int64
	diskBytes atomic.Int64
}

func mediaRequest(ctx context.Context, address string, headers map[string]string, interval, tag string) (*http.Response, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil {
		return nil, errors.New("invalid media URL")
	}
	request.Header.Set("User-Agent", music.Agent)
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	request.Header.Del("Range")
	request.Header.Del("If-Range")
	request.Header.Set("Accept-Encoding", "identity")
	if interval != "" {
		request.Header.Set("Range", interval)
	}
	if tag != "" {
		request.Header.Set("If-Range", tag)
	}
	response, err := music.HeadersStay(http.DefaultClient, request.URL.Hostname(), headers).Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, errMediaInterrupted
	}
	return response, nil
}

func parseByteRange(header string) (start, end, size int64, ok bool) {
	if !strings.HasPrefix(header, "bytes ") {
		return
	}
	bounds, total, found := strings.Cut(header[6:], "/")
	if !found {
		return
	}
	first, last, found := strings.Cut(bounds, "-")
	if !found {
		return
	}
	start, firstErr := strconv.ParseInt(first, 10, 64)
	end, lastErr := strconv.ParseInt(last, 10, 64)
	size, sizeErr := strconv.ParseInt(total, 10, 64)
	return start, end, size, firstErr == nil && lastErr == nil && sizeErr == nil && start >= 0 && end >= start && end < size
}

func (r *byteRanges) fetch(start int64) ([]byte, int64, string, error) {
	last := start + (mediaBlock - 1)
	if r.size > 0 {
		last = min(last, r.size-1)
	}
	if last < start {
		return nil, 0, "", io.EOF
	}
	var err error
	for attempt := 0; attempt < 3; attempt++ {
		var body []byte
		var size int64
		var tag string
		body, size, tag, err = r.fetchOnce(start, last)
		if !retryMedia(err) || attempt == 2 {
			return body, size, tag, err
		}
		select {
		case <-r.ctx.Done():
			return nil, 0, "", r.ctx.Err()
		case <-time.After(time.Duration(attempt+1) * 180 * time.Millisecond):
		}
	}
	return nil, 0, "", err
}

func (r *byteRanges) fetchOnce(first, last int64) ([]byte, int64, string, error) {
	ctx, cancel := context.WithTimeout(r.ctx, 12*time.Second)
	defer cancel()
	response, err := mediaRequest(ctx, r.url, r.headers, fmt.Sprintf("bytes=%d-%d", first, last), r.tag)
	if err != nil {
		if r.ctx.Err() == nil && errors.Is(err, context.DeadlineExceeded) {
			err = errMediaInterrupted
		}
		return nil, 0, "", err
	}
	defer response.Body.Close()
	if response.StatusCode != 206 {
		if r.tag != "" && (response.StatusCode == 200 || response.StatusCode == 416) {
			return nil, 0, "", errMediaChanged
		}
		return nil, 0, "", mediaHTTPError(response.StatusCode)
	}
	start, end, size, ok := parseByteRange(response.Header.Get("Content-Range"))
	tag := response.Header.Get("ETag")
	encoding := response.Header.Get("Content-Encoding")
	if !ok || start != first || end != min(last, size-1) || encoding != "" && encoding != "identity" ||
		r.size > 0 && size != r.size || r.tag != "" && tag != "" && tag != r.tag {
		return nil, 0, "", errMediaChanged
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, end-start+2))
	if err != nil || int64(len(body)) != end-start+1 {
		return nil, 0, "", errMediaInterrupted
	}
	return body, size, tag, nil
}

func (r *byteRanges) readAt(dst []byte, offset int64) (int, error) {
	if offset < 0 {
		return 0, errors.New("invalid media offset")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	read := 0
	for read < len(dst) && offset < r.size {
		if err := r.ctx.Err(); err != nil {
			return read, err
		}
		start := offset / mediaBlock * mediaBlock
		count := int(min(int64(len(dst)-read), min(mediaBlock-(offset-start), r.size-offset)))
		if r.file != nil && r.onDisk[start] {
			got, err := r.file.ReadAt(dst[read:read+count], offset)
			if err != nil {
				return read + got, err
			}
		} else {
			data := r.blocks[start]
			if data == nil {
				var err error
				data, _, _, err = r.fetch(start)
				if err != nil {
					return read, err
				}
				r.store(start, data)
			}
			copy(dst[read:read+count], data[offset-start:offset-start+int64(count)])
		}
		read += count
		offset += int64(count)
	}
	if read < len(dst) {
		return read, io.EOF
	}
	return read, nil
}

func (r *byteRanges) store(start int64, data []byte) {
	if r.file != nil {
		if _, err := r.file.WriteAt(data, start); err == nil {
			r.onDisk[start] = true
			r.covered.Add(int64(len(data)))
			r.diskBytes.Add(int64(len(data)))
			return
		}
	}
	if len(r.order) == 16 {
		old := r.order[0]
		r.covered.Add(-int64(len(r.blocks[old])))
		delete(r.blocks, old)
		r.order = r.order[1:]
	}
	r.blocks[start] = data
	r.order = append(r.order, start)
	r.covered.Add(int64(len(data)))
}

func (d *download) tryRanges(ctx context.Context, address string, headers map[string]string, budget int64) bool {
	ranges := &byteRanges{ctx: ctx, url: address, headers: headers, blocks: map[int64][]byte{}, onDisk: map[int64]bool{}}
	head, size, tag, err := ranges.fetch(0)
	if err != nil || len(tag) < 2 || tag[0] != '"' || tag[len(tag)-1] != '"' || len(head) < 12 ||
		string(head[4:8]) != "ftyp" && string(head[:4]) != "fLaC" {
		return false
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if ctx.Err() != nil {
		return true
	}
	ranges.size, ranges.tag = size, tag
	if size <= budget {
		ranges.file = d.file
	}
	ranges.store(0, head)
	d.ranges, d.data, d.n, d.size, d.done = ranges, append([]byte(nil), head[:12]...), 12, size, true
	d.cond.Broadcast()
	return true
}
