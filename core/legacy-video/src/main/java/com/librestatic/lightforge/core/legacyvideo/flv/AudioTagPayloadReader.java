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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.AacUtil;
import androidx.media3.extractor.TrackOutput;
import java.util.Collections;

/** Parses audio tags from an FLV stream and extracts AAC, MP3, G.711 and PCM frames. */
@UnstableApi
/* package */ final class AudioTagPayloadReader extends TagPayloadReader {

  // Lightforge change: linear PCM support.
  private static final int AUDIO_FORMAT_PCM_PLATFORM_ENDIAN = 0;
  private static final int AUDIO_FORMAT_PCM_LITTLE_ENDIAN = 3;
  private static final int AUDIO_FORMAT_MP3 = 2;
  private static final int AUDIO_FORMAT_ALAW = 7;
  private static final int AUDIO_FORMAT_ULAW = 8;
  private static final int AUDIO_FORMAT_AAC = 10;

  private static final int AAC_PACKET_TYPE_SEQUENCE_HEADER = 0;
  private static final int AAC_PACKET_TYPE_AAC_RAW = 1;

  private static final int[] AUDIO_SAMPLING_RATE_TABLE = new int[] {5512, 11025, 22050, 44100};

  // State variables
  private boolean hasParsedAudioDataHeader;
  private boolean hasOutputFormat;
  private int audioFormat;
  private boolean isUnsupported;

  /** Returns whether {@code soundHeader} (the first byte of an audio tag) is a supported codec. */
  /* package */ static boolean isSupportedSoundFormat(int soundHeader) {
    switch ((soundHeader >> 4) & 0x0F) {
      case AUDIO_FORMAT_PCM_PLATFORM_ENDIAN:
      case AUDIO_FORMAT_PCM_LITTLE_ENDIAN:
      case AUDIO_FORMAT_MP3:
      case AUDIO_FORMAT_ALAW:
      case AUDIO_FORMAT_ULAW:
      case AUDIO_FORMAT_AAC:
        return true;
      default:
        return false;
    }
  }

  public AudioTagPayloadReader(TrackOutput output) {
    super(output);
  }

  @Override
  public void seek() {
    // Do nothing.
  }

  @Override
  protected boolean parseHeader(ParsableByteArray data) throws ParserException {
    if (!hasParsedAudioDataHeader) {
      int header = data.readUnsignedByte();
      audioFormat = (header >> 4) & 0x0F;
      if (audioFormat == AUDIO_FORMAT_MP3) {
        int sampleRateIndex = (header >> 2) & 0x03;
        int sampleRate = AUDIO_SAMPLING_RATE_TABLE[sampleRateIndex];
        Format format =
            new Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_FLV)
                .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                .setChannelCount(1)
                .setSampleRate(sampleRate)
                .build();
        output.format(format);
        hasOutputFormat = true;
      } else if (audioFormat == AUDIO_FORMAT_ALAW || audioFormat == AUDIO_FORMAT_ULAW) {
        String mimeType =
            audioFormat == AUDIO_FORMAT_ALAW ? MimeTypes.AUDIO_ALAW : MimeTypes.AUDIO_MLAW;
        Format format =
            new Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_FLV)
                .setSampleMimeType(mimeType)
                .setChannelCount(1)
                .setSampleRate(8000)
                .build();
        output.format(format);
        hasOutputFormat = true;
      } else if (audioFormat == AUDIO_FORMAT_PCM_PLATFORM_ENDIAN
          || audioFormat == AUDIO_FORMAT_PCM_LITTLE_ENDIAN) {
        // Platform endian (0) is treated as little endian.
        boolean is16Bit = ((header >> 1) & 0x01) != 0;
        boolean isStereo = (header & 0x01) != 0;
        Format format =
            new Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_FLV)
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(is16Bit ? C.ENCODING_PCM_16BIT : C.ENCODING_PCM_8BIT)
                .setChannelCount(isStereo ? 2 : 1)
                .setSampleRate(AUDIO_SAMPLING_RATE_TABLE[(header >> 2) & 0x03])
                .build();
        output.format(format);
        hasOutputFormat = true;
      } else if (audioFormat != AUDIO_FORMAT_AAC) {
        // Lightforge change: skip unsupported audio instead of failing the whole file.
        isUnsupported = true;
      }
      hasParsedAudioDataHeader = true;
    } else {
      // Skip header if it was parsed previously.
      data.skipBytes(1);
    }
    return !isUnsupported;
  }

  @Override
  protected boolean parsePayload(ParsableByteArray data, long timeUs) throws ParserException {
    if (audioFormat == AUDIO_FORMAT_MP3
        || audioFormat == AUDIO_FORMAT_PCM_PLATFORM_ENDIAN
        || audioFormat == AUDIO_FORMAT_PCM_LITTLE_ENDIAN) {
      int sampleSize = data.bytesLeft();
      output.sampleData(data, sampleSize);
      output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null);
      return true;
    } else {
      int packetType = data.readUnsignedByte();
      if (packetType == AAC_PACKET_TYPE_SEQUENCE_HEADER && !hasOutputFormat) {
        // Parse the sequence header.
        byte[] audioSpecificConfig = new byte[data.bytesLeft()];
        data.readBytes(audioSpecificConfig, 0, audioSpecificConfig.length);
        AacUtil.Config aacConfig = AacUtil.parseAudioSpecificConfig(audioSpecificConfig);
        Format format =
            new Format.Builder()
                .setContainerMimeType(MimeTypes.VIDEO_FLV)
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setCodecs(aacConfig.codecs)
                .setChannelCount(aacConfig.channelCount)
                .setSampleRate(aacConfig.sampleRateHz)
                .setInitializationData(Collections.singletonList(audioSpecificConfig))
                .build();
        output.format(format);
        hasOutputFormat = true;
        return false;
      } else if (audioFormat != AUDIO_FORMAT_AAC || packetType == AAC_PACKET_TYPE_AAC_RAW) {
        int sampleSize = data.bytesLeft();
        output.sampleData(data, sampleSize);
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null);
        return true;
      } else {
        return false;
      }
    }
  }
}
