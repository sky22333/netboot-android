include(FetchContent)

set(WIMLIB_VERSION "1.14.5")

FetchContent_Declare(wim
    URL "https://wimlib.net/downloads/wimlib-${WIMLIB_VERSION}.tar.gz"
    URL_HASH SHA256=84221a3abd5b91228f15f8e6065c335a336237b5738197b75bf419eea561a194
)
FetchContent_Declare(udf
    URL "https://download.videolan.org/pub/videolan/libudfread/libudfread-1.2.0.tar.xz"
    URL_HASH SHA256=bb477cbd4cfbfc7787d9d05b71ee5e70430f5cfebf1297497f7e83547958050f
)
FetchContent_Declare(fat
    URL "https://elm-chan.org/fsw/ff/arc/ff16.zip"
    URL_HASH SHA256=99f7dc1f7e095356e4a9e3dbe29959090d8b948afe2bbc5441e52fdf4b85449e
)
