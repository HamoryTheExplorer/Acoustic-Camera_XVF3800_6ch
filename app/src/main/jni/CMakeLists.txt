cmake_minimum_required(VERSION 3.22.1)

project("usb_iso_transfer")

add_library(usb_iso_transfer SHARED
    usb_iso_transfer.c
)

find_library(log-lib log)
target_link_libraries(usb_iso_transfer ${log-lib})
