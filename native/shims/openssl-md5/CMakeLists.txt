# Stands in for OpenSSL::Crypto in the OrcaMicro engine build (MD5 only).
add_library(orcamicro_openssl_md5 STATIC md5.c)
target_include_directories(orcamicro_openssl_md5 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
set_target_properties(orcamicro_openssl_md5 PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(OpenSSL::Crypto ALIAS orcamicro_openssl_md5)
add_library(OpenSSL::SSL ALIAS orcamicro_openssl_md5)
