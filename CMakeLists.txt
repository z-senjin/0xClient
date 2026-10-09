cmake_minimum_required(VERSION 3.20)
project(0xClient CXX RC)   # RC: launcher/0xClient.rc carries the window and exe icon

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The game is 64-bit, so we must be too. A 32-bit DLL will simply be refused at injection time, which
# looks like "nothing happened" and wastes an evening.
if(CMAKE_SIZEOF_VOID_P EQUAL 4)
  message(FATAL_ERROR "0xClient must be built 64-bit -- the game is x64 and will reject a 32-bit DLL.")
endif()

# JNI headers. We only need the headers to compile; jvm.dll is loaded at runtime from the path in
# 0xclient.ini, so the machine that BUILDS this does not have to be the one that runs it.
find_package(JNI QUIET)
if(NOT JNI_FOUND)
  # Cross-compiling with llvm-mingw (see tools/wine.md): FindJNI cannot work here -- there is no
  # target JVM to link (we LoadLibrary jvm.dll at runtime anyway) and a host Linux JDK only ships
  # the linux jni_md.h. So take the headers from $JAVA_HOME and generate the win32 jni_md.h. It
  # must be the WINDOWS one: long is 4 bytes on Windows, so "typedef long jlong" (the linux
  # header) would make jlong 4 bytes and silently truncate every project() return.
  if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME is not set and FindJNI found nothing -- set it to a JDK.")
  endif()
  set(JNI_GENERATED "${CMAKE_BINARY_DIR}/jni-include")
  file(MAKE_DIRECTORY "${JNI_GENERATED}")
  file(WRITE "${JNI_GENERATED}/jni_md.h" "\
#ifndef _JNI_MD_H
#define _JNI_MD_H
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
#endif
")
  set(JNI_INCLUDE_DIRS "$ENV{JAVA_HOME}/include" "${JNI_GENERATED}")
  message(STATUS "JNI (cross, generated jni_md.h): ${JNI_INCLUDE_DIRS}")
endif()

# TARGET NAMES MUST NOT DIFFER ONLY BY CASE. They used to be `0xclient` and `0xClient`, which the
# Makefile and Ninja generators handle fine -- but the Visual Studio generator writes one .vcxproj per
# target, named after it, and Windows filenames are case-insensitive. `0xClient.vcxproj` therefore
# OVERWROTE `0xclient.vcxproj`, both targets became the launcher, and no DLL was ever produced. The
# build still reported success, because from MSBuild's point of view both projects built perfectly.
#
# The internal names below are distinct; OUTPUT_NAME keeps the shipped filenames exactly as they were.

# ---------------------------------------------------------------------------------------------------
# Dear ImGui + the software rasterizer, shared by BOTH binaries.
#
# An OBJECT library, not a plain source list, because the launcher and the DLL must agree on one
# compiled ImGui (two copies would mean two sets of statics -- two allocators, two atlas states --
# if they ever ended up in one process). An interface target cannot carry compiled objects, and
# listing the sources twice invites the copies drifting apart, so: one object library, linked by
# both. imgui_demo.cpp is deliberately absent -- it drags in half of the API surface we do not use
# and its ShowDemoWindow is exactly what a stray Ctrl+click should not be able to reach.
# ---------------------------------------------------------------------------------------------------
add_library(oxc_imgui OBJECT
    third_party/imgui/imgui.cpp
    third_party/imgui/imgui_draw.cpp
    third_party/imgui/imgui_tables.cpp
    third_party/imgui/imgui_widgets.cpp
    client/imgui_sw.cpp)
target_include_directories(oxc_imgui PUBLIC third_party/imgui client)

# ---------------------------------------------------------------------------------------------------
# 0xclient.dll -- injected into the game
# ---------------------------------------------------------------------------------------------------
add_library(oxc_dll SHARED client/dllmain.cpp)
target_include_directories(oxc_dll PRIVATE client ${JNI_INCLUDE_DIRS})
# version: GetFileVersionInfoW, for the build fingerprint check against the host exe (offsets.hpp).
target_link_libraries(oxc_dll PRIVATE user32 gdi32 version winhttp bcrypt oxc_imgui)
set_target_properties(oxc_dll PROPERTIES PREFIX "" OUTPUT_NAME "0xclient")

if(MINGW)
  # llvm-mingw links libc++/libunwind as DLLs by default, which would ship two extra runtime files
  # beside 0xclient.dll. -static folds them in and keeps the dist one DLL, same as the MSVC build.
  target_link_options(oxc_dll PRIVATE -static)
endif()

# ---------------------------------------------------------------------------------------------------
# 0xClient.exe -- the launcher with the button
# ---------------------------------------------------------------------------------------------------
# WebView2 comes from the official Microsoft NuGet package so the SDK version is reproducible.
include(FetchContent)
set(OXC_WEBVIEW2_VERSION "1.0.3537.50" CACHE STRING "Microsoft WebView2 SDK version")
FetchContent_Declare(webview2_sdk
    URL "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/${OXC_WEBVIEW2_VERSION}")
FetchContent_MakeAvailable(webview2_sdk)
find_path(OXC_WEBVIEW2_INCLUDE WebView2.h PATHS "${webview2_sdk_SOURCE_DIR}/build/native/include" NO_DEFAULT_PATH)
find_library(OXC_WEBVIEW2_LOADER WebView2LoaderStatic PATHS "${webview2_sdk_SOURCE_DIR}/build/native/x64" NO_DEFAULT_PATH)
if(NOT OXC_WEBVIEW2_INCLUDE OR NOT OXC_WEBVIEW2_LOADER)
  message(FATAL_ERROR "The WebView2 SDK package is missing WebView2.h or WebView2LoaderStatic.lib")
endif()
add_executable(oxc_launcher WIN32
    launcher/main.cpp
    launcher/0xClient.rc
    launcher/accounts/account_store.cpp
    launcher/accounts/dpapi_credentials.cpp
    launcher/accounts/http_client.cpp
    launcher/accounts/jagex_auth.cpp
    launcher/accounts/jagex_auth_window.cpp
    launcher/accounts/osclient_launcher.cpp)
target_include_directories(oxc_launcher PRIVATE launcher "${OXC_WEBVIEW2_INCLUDE}")
target_link_libraries(oxc_launcher PRIVATE user32 gdi32 ole32 crypt32 bcrypt winhttp "${OXC_WEBVIEW2_LOADER}" oxc_imgui)
set_target_properties(oxc_launcher PROPERTIES OUTPUT_NAME "0xClient")

if(MINGW)
  # Same story as oxc_dll above, mirrored here: the launcher uses std::string/std::vector, and an
  # exe that imports libc++.dll/libunwind.dll fails to LOAD under Wine unless those DLLs happen to
  # be in the prefix (probed: "err:module:import_dll Library libc++.dll ... needed by
  # 0xClient.exe"). The old launcher got away without this only because it touched no C++
  # runtime type with a symbol to resolve.
  target_link_options(oxc_launcher PRIVATE -static)
endif()

# Drop both next to each other so the launcher can find the DLL by sitting beside it, and so the DLL
# can find 0xclient.jar the same way. Gradle passes OXC_DIST; a bare cmake run gets a sane default.
if(NOT DEFINED OXC_DIST)
  set(OXC_DIST "${CMAKE_BINARY_DIR}/dist")
endif()

# Multi-config generators (Visual Studio, Xcode) APPEND the config name to the plain
# RUNTIME_OUTPUT_DIRECTORY, so setting only that puts the binaries in dist/Release while the jar and
# the ini land in dist/ -- the DLL then loads and cannot find the jar, which surfaces as
# "oxclient/OxClient not found" and looks like a Java problem. Setting the per-config variables as well
# suppresses that suffix, so every generator agrees on one directory.
foreach(cfg "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
  set_target_properties(oxc_dll oxc_launcher PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY${cfg} "${OXC_DIST}"
      LIBRARY_OUTPUT_DIRECTORY${cfg} "${OXC_DIST}")
endforeach()
