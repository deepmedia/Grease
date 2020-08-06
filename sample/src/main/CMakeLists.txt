cmake_minimum_required(VERSION 3.4.1)
add_library(grease SHARED cc/grease-api.cc )
find_library(android-logging log)
target_link_libraries(grease ${android-logging})