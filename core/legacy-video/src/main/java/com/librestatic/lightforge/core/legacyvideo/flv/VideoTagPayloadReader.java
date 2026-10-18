/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Forked from androidx.media3 1.11.0 (androidx.media3.extractor.flv); see "Lightforge changes" comments.
package com.librestatic.lightforge.core.legacyvideo.flv;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import com.librestatic.lightforge.core.legacyvideo.LegacyVideoMimeTypes;
import java.util.Collections;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.extractor.AvcConfig;
import androidx.media3.extractor.TrackOutput;

/** Parses video tags from an FLV stream and extracts H.264, Sorenson Spark and VP6 frames. */
@UnstableApi
/* package */ final class VideoTagPayloadReader extends TagPayloadReader {

  // Video codec.
  private static final int VIDEO_CODEC_SORENSON_H263 = 2;
  private static final int VIDEO_CODEC_VP6 = 4;
  private static final int VIDEO_CODEC_VP6_ALPHA = 5;
  private static final int VIDEO_CODEC_AVC = 7;

  // Frame types.
  private static final int VIDEO_FRAME_KEYFRAME = 1;
  private static final int VIDEO_FRAME_VIDEO_INFO = 5;

  // Packet types.
  private static final int AVC_PACKET_TYPE_SEQUENCE_HEADER = 0;
  private static final int AVC_PACKET_TYPE_AVC_NALU = 1;

  // Temporary arrays.
  private final ParsableByteArray nalStartCode;
  private final ParsableByteArray nalLength;
  private int nalUnitLengthFieldLength;

  // State variables.
  private boolean hasOutputFormat;
  private boolean hasOutputKeyframe;
  private int frameType;
  private int videoCodec;

  /**
   * @param output A {@link TrackOutput} to which samples should be written.
   */
  public VideoTagPayloadReader(TrackOutput output) {
    super(output);
    nalStartCode = new ParsableByteArray(NalUnitUtil.NAL_START_CODE);
    nalLength = new ParsableByteArray(4);
  }

  @Override
  public void seek() {
    hasOutputKeyframe = false;
  }

  @Override
  protected boolean parseHeader(ParsableByteArray data) throws UnsupportedFormatException {
    int header = data.readUnsignedByte();
    int frameType = (header >> 4) & 0x0F;
    int videoCodec = (header & 0x0F);
    // Lightforge change: also accept Sorenson Spark and VP6 (with and without alpha).
    if (videoCodec != VIDEO_CODEC_AVC
        && videoCodec != VIDEO_CODEC_SORENSON_H263
        && videoCodec != VIDEO_CODEC_VP6
        && videoCodec != VIDEO_CODEC_VP6_ALPHA) {
      throw new UnsupportedFormatException("Video format not supported: " + videoCodec);
    }
    this.frameType = frameType;
    this.videoCodec = videoCodec;
    return (frameType != VIDEO_FRAME_VIDEO_INFO);
  }

  @Override
  protected boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException {
    if (videoCodec != VIDEO_CODEC_AVC) {
      return parseLegacyPayload(data, timeUs);
    }
    int packetType = data.readUnsignedByte();
    int compositionTimeMs = data.readInt24();

    timeUs += compositionTimeMs * 1000L;
    // Parse avc sequence header in case this was not done before.
    if (packetType == AVC_PACKET_TYPE_SEQUENCE_HEADER && !hasOutputFormat) {
      ParsableByteArray videoSequence = new ParsableByteArray(new byte[data.bytesLeft()]);
      data.readBytes(videoSequence.getData(), 0, data.bytesLeft());
      AvcConfig avcConfig = AvcConfig.parse(videoSequence);
      nalUnitLengthFieldLength = avcConfig.nalUnitLengthFieldLength;
      // Construct and output the format.
      Format format =
          new Format.Builder()
              .setContainerMimeType(MimeTypes.VIDEO_FLV)
              .setSampleMimeType(MimeTypes.VIDEO_H264)
              .setCodecs(avcConfig.codecs)
              .setWidth(avcConfig.width)
              .setHeight(avcConfig.height)
              .setPixelWidthHeightRatio(avcConfig.pixelWidthHeightRatio)
              .setInitializationData(avcConfig.initializationData)
              .build();
      output.format(format);
      hasOutputFormat = true;
      return false;
    } else if (packetType == AVC_PACKET_TYPE_AVC_NALU && hasOutputFormat) {
      boolean isKeyframe = frameType == VIDEO_FRAME_KEYFRAME;
      if (!hasOutputKeyframe && !isKeyframe) {
        return false;
      }
      // TODO: Deduplicate with Mp4Extractor.
      // Zero the top three bytes of the array that we'll use to decode nal unit lengths, in case
      // they're only 1 or 2 bytes long.
      byte[] nalLengthData = nalLength.getData();
      nalLengthData[0] = 0;
      nalLengthData[1] = 0;
      nalLengthData[2] = 0;
      int nalUnitLengthFieldLengthDiff = 4 - nalUnitLengthFieldLength;
      // NAL units are length delimited, but the decoder requires start code delimited units.
      // Loop until we've written the sample to the track output, replacing length delimiters with
      // start codes as we encounter them.
      int bytesWritten = 0;
      int bytesToWrite;
      while (data.bytesLeft() > 0) {
        // Read the NAL length so that we know where we find the next one.
        data.readBytes(nalLength.getData(), nalUnitLengthFieldLengthDiff, nalUnitLengthFieldLength);
        nalLength.setPosition(0);
        bytesToWrite = nalLength.readUnsignedIntToInt();

        // Write a start code for the current NAL unit.
        nalStartCode.setPosition(0);
        output.sampleData(nalStartCode, 4);
        bytesWritten += 4;

        // Write the payload of the NAL unit.
        output.sampleData(data, bytesToWrite);
        bytesWritten += bytesToWrite;
      }
      output.sampleMetadata(
          timeUs, isKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0, bytesWritten, 0, null);
      hasOutputKeyframe = true;
      return true;
    } else {
      return false;
    }
  }

  // Lightforge addition: Sorenson Spark and VP6 payloads.

  private boolean parseLegacyPayload(ParsableByteArray data, long timeUs) {
    boolean isKeyframe = frameType == VIDEO_FRAME_KEYFRAME;
    if (!hasOutputKeyframe && !isKeyframe) {
      return false;
    }
    @Nullable byte[] adjustment = null;
    if (videoCodec != VIDEO_CODEC_SORENSON_H263) {
      if (data.bytesLeft() < 1) {
        return false;
      }
      // High nibble: horizontal crop, low nibble: vertical crop. FFmpeg reads it from extradata[0].
      adjustment = new byte[] {(byte) data.readUnsignedByte()};
    }
    int sampleSize = data.bytesLeft();
    if (sampleSize == 0) {
      return false;
    }
    if (!hasOutputFormat) {
      int[] size =
          videoCodec == VIDEO_CODEC_SORENSON_H263
              ? parseSorensonSize(data)
              : parseVp6Size(data, adjustment[0] & 0xFF);
      Format.Builder format =
          new Format.Builder()
              .setContainerMimeType(MimeTypes.VIDEO_FLV)
              .setSampleMimeType(legacyMimeType());
      if (size != null) {
        format.setWidth(size[0]).setHeight(size[1]);
      }
      if (adjustment != null) {
        format.setInitializationData(Collections.singletonList(adjustment));
      }
      output.format(format.build());
      hasOutputFormat = true;
    }
    output.sampleData(data, sampleSize);
    output.sampleMetadata(timeUs, isKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0, sampleSize, 0, null);
    hasOutputKeyframe = true;
    return true;
  }

  private String legacyMimeType() {
    switch (videoCodec) {
      case VIDEO_CODEC_SORENSON_H263:
        return LegacyVideoMimeTypes.VIDEO_FLV1;
      case VIDEO_CODEC_VP6:
        return LegacyVideoMimeTypes.VIDEO_VP6F;
      default:
        return LegacyVideoMimeTypes.VIDEO_VP6A;
    }
  }

  /** Returns {width, height} from a Sorenson picture header at the data position, or null. */
  @Nullable
  private static int[] parseSorensonSize(ParsableByteArray data) {
    if (data.bytesLeft() < 9) {
      return null;
    }
    ParsableBitArray bits =
        new ParsableBitArray(data.getData(), data.getPosition() + data.bytesLeft());
    bits.setPosition(data.getPosition() * 8);
    if (bits.readBits(17) != 1) {
      return null;
    }
    bits.skipBits(5 + 8); // version, temporal reference
    int sizeCode = bits.readBits(3);
    switch (sizeCode) {
      case 0:
        return new int[] {bits.readBits(8), bits.readBits(8)};
      case 1:
        return new int[] {bits.readBits(16), bits.readBits(16)};
      case 2:
        return new int[] {352, 288};
      case 3:
        return new int[] {176, 144};
      case 4:
        return new int[] {128, 96};
      case 5:
        return new int[] {320, 240};
      case 6:
        return new int[] {160, 120};
      default:
        return null;
    }
  }

  /**
   * Returns {width, height} from a VP6 keyframe header (see FFmpeg vp6_parse_header), or null. For
   * VP6 with alpha the 3 byte OffsetToAlpha precedes the colour frame.
   */
  @Nullable
  private int[] parseVp6Size(ParsableByteArray data, int adjustment) {
    byte[] d = data.getData();
    int p = data.getPosition() + (videoCodec == VIDEO_CODEC_VP6_ALPHA ? 3 : 0);
    int end = data.getPosition() + data.bytesLeft();
    if (end - p < 6 || (d[p] & 0x80) != 0) {
      return null; // Not a keyframe.
    }
    boolean separatedCoeff = (d[p] & 1) != 0;
    boolean filterHeader = (d[p + 1] & 0x06) != 0;
    if (separatedCoeff || !filterHeader) {
      p += 2; // Skip the coefficient partition offset.
      if (end - p < 6) {
        return null;
      }
    }
    int rows = d[p + 2] & 0xFF;
    int cols = d[p + 3] & 0xFF;
    if (rows == 0 || cols == 0) {
      return null;
    }
    return new int[] {cols * 16 - (adjustment >> 4), rows * 16 - (adjustment & 0x0F)};
  }
}
