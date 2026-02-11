cmake_minimum_required(VERSION 3.22.1)
project(paddleocrncnn)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED OPENCV_ROOT)
    message(FATAL_ERROR "OPENCV_ROOT is not set")
endif()
if(NOT DEFINED NCNN_ROOT)
    message(FATAL_ERROR "NCNN_ROOT is not set")
endif()

set(OpenCV_DIR ${OPENCV_ROOT}/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(
    paddleocrncnn
    SHARED
    paddleocr_ncnn.cpp
    ppocrv5.cpp
    kmp_compat.cpp
)

target_include_directories(
    paddleocrncnn
    PRIVATE
    ${OpenCV_INCLUDE_DIRS}
)

target_link_libraries(
    paddleocrncnn
    ncnn
    ${OpenCV_LIBS}
    jnigraphics
    omp
    c++_shared
    log
)
