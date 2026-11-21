cmake_minimum_required(VERSION 3.22.1)
project(mbquickjs LANGUAGES C)

set(MBJS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ios/Sources/MBJavaScript)
set(QUICKJS ${MBJS}/vendor)
add_library(mbquickjs SHARED
    quickjs_jni.c
    ${MBJS}/MBJavaScript.c
    ${QUICKJS}/quickjs.c
    ${QUICKJS}/dtoa.c
    ${QUICKJS}/libregexp.c
    ${QUICKJS}/libunicode.c
    ${QUICKJS}/cutils.c
)
target_include_directories(mbquickjs PRIVATE ${MBJS}/include)
target_compile_definitions(mbquickjs PRIVATE CONFIG_VERSION="2026-06-04")
set_target_properties(mbquickjs PROPERTIES C_STANDARD 11 C_VISIBILITY_PRESET hidden)
target_link_libraries(mbquickjs PRIVATE m)
