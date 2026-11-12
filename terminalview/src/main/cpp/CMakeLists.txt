cmake_minimum_required(VERSION 3.22.1)

project(unitool_terminal LANGUAGES C)

add_library(unitool-term SHARED
    ../jni/term.c
)

target_compile_options(unitool-term PRIVATE
    -std=c11
    -Wall
    -Wextra
    -Werror
    -Os
    -fno-stack-protector
)

target_link_options(unitool-term PRIVATE
    -Wl,--gc-sections
)
