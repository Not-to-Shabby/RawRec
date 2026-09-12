cmake_minimum_required(VERSION 3.22)
project(rawrec LANGUAGES C CXX)

set(ZSTD_DIR third_party/zstd)

file(GLOB ZSTD_SOURCES
    ${ZSTD_DIR}/common/*.c
    ${ZSTD_DIR}/compress/*.c
    ${ZSTD_DIR}/decompress/*.c)

add_library(zstd STATIC ${ZSTD_SOURCES})
target_include_directories(zstd PUBLIC ${ZSTD_DIR} ${ZSTD_DIR}/common)
target_compile_options(zstd PRIVATE -O3 -Wno-deprecated-declarations)
target_compile_definitions(zstd PRIVATE ZSTD_MULTITHREAD)

add_library(rawrec SHARED rawrec_jni.cpp)
target_link_libraries(rawrec PRIVATE zstd log)
target_compile_options(rawrec PRIVATE -O3 -std=c++17)
target_link_options(rawrec PRIVATE "-Wl,-z,max-page-size=16384")
