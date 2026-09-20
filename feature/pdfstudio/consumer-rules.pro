# PDFBox-Android's optional JPEG 2000 codec is not bundled. JPX decoding checks
# Class.forName and throws MissingImageReaderException before referencing it.
# UGallery never invokes the JPX encoder: generated images use JPEG/Flate, and
# imported static PDF page streams are cloned without decoding their images.
# Keep this specific; do not suppress missing classes for the entire parser/BC.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
