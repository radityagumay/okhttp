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
import java.net.ProtocolException;
import java.util.Random;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import org.junit.Test;

import static com.squareup.okhttp.WebSocket.PayloadType;
import static com.squareup.okhttp.WebSocket.PayloadType.BINARY;
import static com.squareup.okhttp.WebSocket.PayloadType.TEXT;
import static com.squareup.okhttp.internal.ws.RecordingWebSocketListener.Message;
import static com.squareup.okhttp.internal.ws.RecordingWebSocketListener.MessageDelegate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class WebSocketReaderTest {
  private final Buffer data = new Buffer();
  private final RecordingWebSocketListener listener = new RecordingWebSocketListener();

  // Mutually exclusive. Use the one corresponding to the peer whose behavior you wish to test.
  private final WebSocketReader serverReader = new WebSocketReader(false, data, listener);
  private final WebSocketReader clientReader = new WebSocketReader(true, data, listener);

  @Test public void controlFramesMustBeFinal() throws IOException {
    data.write(ByteString.decodeHex("0a00")); // Empty ping.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Control frames must be final.", e.getMessage());
    }
  }

  @Test public void reservedFlagsAreUnsupported() throws IOException {
    data.write(ByteString.decodeHex("9a00")); // Empty ping, flag 1 set.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
    data.clear();
    data.write(ByteString.decodeHex("aa00")); // Empty ping, flag 2 set.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
    data.clear();
    data.write(ByteString.decodeHex("ca00")); // Empty ping, flag 3 set.
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Reserved flags are unsupported.", e.getMessage());
    }
  }

  @Test public void clientSentFramesMustBeMasked() throws IOException {
    data.write(ByteString.decodeHex("8100"));
    try {
      serverReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Client-sent frames must be masked. Server sent must not.", e.getMessage());
    }
  }

  @Test public void serverSentFramesMustNotBeMasked() throws IOException {
    data.write(ByteString.decodeHex("8180"));
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Client-sent frames must be masked. Server sent must not.", e.getMessage());
    }
  }

  @Test public void controlFramePayloadMax() throws IOException {
    data.write(ByteString.decodeHex("8a7e007e"));
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Control frame must be less than 125B.", e.getMessage());
    }
  }

  @Test public void clientSimpleHello() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f"));
    clientReader.readMessage();
    assertTextMessage("Hello");
  }

  @Test public void serverSimpleHello() throws IOException {
    data.write(ByteString.decodeHex("818537fa213d7f9f4d5158"));
    serverReader.readMessage();
    assertTextMessage("Hello");
  }

  @Test public void clientTwoFrameHello() throws IOException {
    data.write(ByteString.decodeHex("010348656c"));
    data.write(ByteString.decodeHex("80026c6f"));
    clientReader.readMessage();
    assertTextMessage("Hello");
  }

  @Test public void clientSimpleBinary() throws IOException {
    byte[] bytes = binaryData(256);
    data.write(ByteString.decodeHex("827E0100")).write(bytes);
    clientReader.readMessage();
    assertBinaryMessage(bytes);
  }

  @Test public void clientTwoFrameBinary() throws IOException {
    byte[] bytes = binaryData(200);
    data.write(ByteString.decodeHex("0264")).write(bytes, 0, 100);
    data.write(ByteString.decodeHex("8064")).write(bytes, 100, 100);
    clientReader.readMessage();
    assertBinaryMessage(bytes);
  }

  @Test public void twoFrameNotContinuation() throws IOException {
    byte[] bytes = binaryData(200);
    data.write(ByteString.decodeHex("0264")).write(bytes, 0, 100);
    data.write(ByteString.decodeHex("8264")).write(bytes, 100, 100);
    try {
      clientReader.readMessage();
      fail();
    } catch (ProtocolException e) {
      assertEquals("Expected continuation opcode. Got: 2", e.getMessage());
    }
  }

  @Test public void noCloseErrors() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f"));
    listener.setNextMessageDelegate(new MessageDelegate() {
      @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
        payload.readAll(new Buffer());
      }
    });
    try {
      clientReader.readMessage();
    } catch (IllegalStateException e) {
      assertEquals("Listener failed to call close on message payload.", e.getMessage());
    }
  }

  @Test public void closeExhaustsMessage() throws IOException {
    data.write(ByteString.decodeHex("810548656c6c6f"));
    data.write(ByteString.decodeHex("810448657921"));

    final Buffer sink = new Buffer();
    listener.setNextMessageDelegate(new MessageDelegate() {
      @Override public void onMessage(BufferedSource payload, PayloadType type) throws IOException {
        payload.read(sink, 3);
        payload.close();
      }
    });

    clientReader.readMessage();
    assertEquals("Hel", sink.readUtf8());

    clientReader.readMessage();
    assertTextMessage("Hey!");
  }

  private void assertTextMessage(String expected) throws IOException {
    Message message = listener.takeMessage();
    assertEquals(TEXT, message.type);
    assertEquals(expected, message.buffer.readUtf8());
  }

  private void assertBinaryMessage(byte[] bytes) throws IOException {
    Message message = listener.takeMessage();
    assertEquals(BINARY, message.type);
    assertEquals(new Buffer().write(bytes), message.buffer);
  }

  private static byte[] binaryData(int length) {
    byte[] junk = new byte[length];
    new Random().nextBytes(junk);
    return junk;
  }
}
