cmake_minimum_required(VERSION 3.22.1)
project(gecko_presence_native LANGUAGES CXX)

find_package(discord_partner_sdk REQUIRED CONFIG)

add_library(gecko_presence_native SHARED
    discord_bridge.cpp
)

target_compile_features(gecko_presence_native PRIVATE cxx_std_17)
target_compile_options(gecko_presence_native PRIVATE -Wall -Wextra -Wpedantic)
target_include_directories(gecko_presence_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(gecko_presence_native
    PRIVATE
    discord_partner_sdk::discord_partner_sdk
    log
)
