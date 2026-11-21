/** 展示与桌面、iOS 和 Android 一致的山海应用图标。 */
import type { IAppIconProps } from './AppIcon.types'

export function AppIcon(props: IAppIconProps) {
  return (
    <img
      class={props.class}
      src="/icon.png"
      alt=""
      aria-hidden="true"
      width={props.size ?? 32}
      height={props.size ?? 32}
      style={{ 'border-radius': '22.5%', 'object-fit': 'cover' }}
    />
  )
}
