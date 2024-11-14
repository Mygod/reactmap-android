cmake_minimum_required(VERSION 3.22.1)
project("brotli")
set(BROTLI_DIR "../../../../brotli")
file(GLOB COMMON_SOURCES "${BROTLI_DIR}/c/common/*.c")
file(GLOB ENC_SOURCES "${BROTLI_DIR}/c/enc/*.c")
add_library(${CMAKE_PROJECT_NAME} SHARED
    ${COMMON_SOURCES}
    ${ENC_SOURCES}
    ${BROTLI_DIR}/java/org/brotli/wrapper/enc/encoder_jni.cc
)
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
    #android
    #log
)
include_directories(
    ${BROTLI_DIR}/c/include
)
