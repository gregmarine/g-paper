cmake_minimum_required(VERSION 3.22.1)
project(gpaper_ebc C)

# The panel pass-through (Phase 28). One C file, no dependencies, no policy — see ebc_jni.c.
add_library(gpaper_ebc SHARED ebc_jni.c)
find_library(log-lib log)
target_link_libraries(gpaper_ebc ${log-lib})
