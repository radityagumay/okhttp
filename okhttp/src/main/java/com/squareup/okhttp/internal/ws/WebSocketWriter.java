/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.internal.ws;

import java.io.IOException;
import java.util.Random;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;
import okio.Timeout;

import static com.squareup.okhttp.WebSocket.PayloadType;
import static com.squareup.okhttp.internal.ws.Protocol.B0_FLAG_CONTROL;
import static com.squareup.okhttp.internal.ws.Protocol.B0_FLAG_FIN;
import static com.squareup.okhttp.internal.ws.Protocol.B1_FLAG_MASK;
import static com.squareup.okhttp.internal.ws.Protocol.MAX_CONTROL_PAYLOAD;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_BINARY;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTINUATION;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTROL_CLOSE;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTROL_PING;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_CONTROL_PONG;
import static com.squareup.okhttp.internal.ws.Protocol.OPCODE_TEXT;
import static com.squareup.okhttp.internal.ws.Protocol.PAYLOAD_LONG;
import static com.squareup.okhttp.internal.ws.Protocol.PAYLOAD_SHORT;
import static com.squareup.okhttp.internal.ws.Protocol.toggleMask;

public final class WebSocketWriter {
  private final boolean isClient;
  /** Writes to this sink must be guarded by synchronizing on the instance. */
  private final BufferedSink sink;
  private final Random random;

  private final FrameSink frameSink = new FrameSink();

  private boolean closed;

  private boolean activeWriter;
  private boolean firstFrame;
  private PayloadType payloadType;

  private final byte[] maskKey = new byte[4];
  private final byte[] maskBuffer = new byte[2048];

  public WebSocketWriter(boolean isClient, BufferedSink sink, Random random) {
    this.isClient = isClient;
    this.sink = sink;
    this.random = random;
  }

  public void writePing(Buffer payload) throws IOException {
    if (closed) throw new IllegalStateException("Closed");

    synchronized (sink) {
      writeControlFrame(OPCODE_CONTROL_PING, payload);
    }
  }

  public void writePong(Buffer payload) throws IOException {
    if (closed) throw new IllegalStateException("Closed");

    synchronized (sink) {
      writeControlFrame(OPCODE_CONTROL_PONG, payload);
    }
  }

  public void writeClose(int code, String reason) throws IOException {
    if (closed) throw new IllegalStateException("Closed");

    Buffer buffer = null;
    if (code != 0) {
      buffer = new Buffer();
      // TODO verify code is a valid value.
      buffer.writeShort(code);
      if (reason != null) {
        buffer.writeUtf8(reason);
      }
    } else if (reason != null) {
      throw new IllegalArgumentException("Code required to include reason.");
    }

    synchronized (sink) {
      writeControlFrame(OPCODE_CONTROL_CLOSE, buffer);
      sink.close();
      closed = true;
    }
  }

  private void writeControlFrame(int opcode, Buffer payload) throws IOException {
    int length = 0;
    if (payload != null) {
      length = (int) payload.size();
      if (length > MAX_CONTROL_PAYLOAD) {
        throw new IllegalArgumentException(
            "Control frame payload must be less than " + MAX_CONTROL_PAYLOAD + "B.");
      }
    }

    int b0 = opcode | B0_FLAG_FIN | B0_FLAG_CONTROL;
    sink.writeByte(b0);

    int b1 = length;
    if (isClient) {
      b1 |= B1_FLAG_MASK;
      sink.writeByte(b1);

      random.nextBytes(maskKey);
      sink.write(maskKey);

      if (payload != null) {
        payload.read(maskBuffer, 0, length);
        toggleMask(maskBuffer, maskKey, length);
        sink.write(maskBuffer, 0, length);
      }
    } else {
      sink.writeByte(b1);

      if (payload != null) {
        sink.writeAll(payload);
      }
    }
  }

  public BufferedSink newMessageWriter(PayloadType type) {
    if (activeWriter) {
      throw new IllegalStateException("Another message writer is active. Did you call close()?");
    }
    activeWriter = true;
    firstFrame = true;
    payloadType = type;
    return Okio.buffer(frameSink);
  }

  public void sendMessage(Buffer payload, PayloadType type) throws IOException {
    if (activeWriter) {
      throw new IllegalStateException("A message writer is active. Did you call close()?");
    }
    firstFrame = true;
    payloadType = type;
    writeFrame(payload, payload.size(), true);
  }

  private void writeFrame(Buffer source, long byteCount, boolean isFinal) throws IOException {
    int opcode = OPCODE_CONTINUATION;
    if (firstFrame) {
      switch (payloadType) {
        case TEXT:
          opcode = OPCODE_TEXT;
          break;
        case BINARY:
          opcode = OPCODE_BINARY;
          break;
        default:
          throw new IllegalStateException("Unknown payload type: " + payloadType);
      }
    }
    firstFrame = false;

    synchronized (sink) {
      int b0 = opcode;
      if (isFinal) {
        b0 |= B0_FLAG_FIN;
      }
      sink.writeByte(b0);

      int b1 = 0;
      if (isClient) {
        b1 |= B1_FLAG_MASK;
        random.nextBytes(maskKey);
      }
      if (byteCount <= 125) {
        b1 |= (int) byteCount;
        sink.writeByte(b1);
      } else if (byteCount <= Short.MAX_VALUE) {
        b1 |= PAYLOAD_SHORT;
        sink.writeByte(b1);
        sink.writeShort((int) byteCount);
      } else {
        b1 |= PAYLOAD_LONG;
        sink.writeByte(b1);
        sink.writeLong(byteCount);
      }

      if (isClient) {
        sink.write(maskKey);

        while (byteCount > 0) {
          long toRead = Math.min(byteCount, maskBuffer.length);
          int read = source.read(maskBuffer, 0, toRead);
          toggleMask(maskBuffer, maskKey, read);
          sink.write(maskBuffer, 0, read);
          byteCount -= read;
        }
      } else {
        sink.write(source, byteCount);
      }
    }
  }

  private final class FrameSink implements Sink {
    @Override public void write(Buffer source, long byteCount) throws IOException {
      writeFrame(source, byteCount, false);
    }

    @Override public void flush() throws IOException {
      synchronized (sink) {
        sink.flush();
      }
    }

    @Override public Timeout timeout() {
      return sink.timeout();
    }

    @Override public void close() throws IOException {
      synchronized (sink) {
        sink.writeByte(0x80);

        if (isClient) {
          sink.writeByte(B1_FLAG_MASK); // 0 length + masked.
          random.nextBytes(maskKey);
          sink.write(maskKey);
        } else {
          sink.writeByte(0x00);
        }
        sink.flush();
      }

      activeWriter = false;
      payloadType = null;
    }
  }
}
