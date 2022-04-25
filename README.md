A Scala 3 project to teach myself functional programming by writing an av1 decoder
that transcodes to raw video, Y'CrCb format to be exact.

# Problem statement
A video decoder is a non trivial piece of software and is a fun way to teach myself functional programming in the large and master Scala 3 along the way.

# Technology
I'm about to use fs2 as much as possible to the point I get the stream of bytes from a file or possible a network socket so I'm not re-inventing the wheel there but I'm *not* using any scodec since that would defeat the purpose. The rest is cats and cats-effects, obviously.

Explicit use of concurrency is welcome if it feels like a good fit for a video decoder later but at the moment it doesn't pop up anywhere.

Tagless final doesn't seem very relevant here. Perhaps later.