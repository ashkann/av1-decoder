A Scala 3 project to teach myself functional programming by writing an av1 decoder
that transcodes to raw video, Y'CrCb format to be exact.

# Problem statement
A video decoder is a non trivial piece of software and is a fun way to teach myself functional programming in the large and master Scala 3 along the way.

# Technology
I'm about to use fs2 as much as possible to get a stream of bytes from a file or possibly from a network socket (in case of an av1 bitstream) but I'm *not* using scodec since that would defeat the purpose. The rest is just cats and cats-effects.

Explicit use of concurrency is most welcome when relevant for an image/video decoder.

Tagless final doesn't seem very relevant here. Perhaps later.

I started with a simple objective of reading a BITMAPV5HEADER, uncompressed 32/24 bpp bitmap file and outputting the pixels, effectively turning it into an RGB23 raw file.

Nex step is to read a base profile AVIF image and output the to some raw format.
AVIF is essentially a restricted HEIF which is considerably more complicated than a BMP.
The AVIF also involves decompressing an AV1 encoded image.

Next step would be to try an AV1 video file, then an AV1 bitstream.