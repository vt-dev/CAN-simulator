package com.visualthreat.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;

import lombok.extern.slf4j.Slf4j;


@Slf4j
public class TCPCANDataPort implements ICANDataPort {
  private static final int CONNECTION_TIMEOUT = 1000;
  private static String ADDRESS = "10.10.10.101";
  private static int PORT = 899;
  private Socket mSocket;
  private Thread mThreadReceiveData;
  private IRAWDataListener dataListener;

  public TCPCANDataPort(IRAWDataListener listener) {
    this.dataListener = listener;
  }

  public void startReadingMessages() {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {
        InputStream inputStream = GetInputStream(mSocket);
        byte[] buffer = new byte[102400];
        while (mSocket != null) {
          try {
            if (inputStream.available() > 0) {
              int bytesRead = inputStream.read(buffer);
              if(dataListener != null){
                dataListener.readData(Arrays.copyOf(buffer, bytesRead));
              }
            } else {
              try {
                Thread.sleep(1);
              } catch (InterruptedException e) {
                return;
              }
            }
          } catch (IOException e) {
            log.error("TCP Reader exiting due to errors", e);
            return;
          }
        }
      }
    };
    mThreadReceiveData = new Thread(runnable);
    mThreadReceiveData.start();
  }

  /**
   * Get inputStreaming
   *
   * @param socket Socket
   * @return inputStream
   */
  private InputStream GetInputStream(Socket socket) {
    if (socket == null)
      return null;
    InputStream inputStream = null;
    try {
      inputStream = socket.getInputStream();
    } catch (IOException e) {
      log.error("Couldn't read data from socket" , e);
    }
    return inputStream;
  }

  /**
   * Get outputStream
   *
   * @param socket Socket
   * @return outputStream
   */
  private OutputStream GetOutputStream(Socket socket) {
    if (socket == null)
      return null;
    OutputStream outputStream = null;
    try {
      outputStream = socket.getOutputStream();
    } catch (IOException e) {
      log.error("Couldn't send data to socket" , e);
    }
    return outputStream;
  }

  @Override
  public boolean writeBytes(byte[] buffer) throws IOException {
    OutputStream mOutputStream = GetOutputStream(mSocket);
    if (mOutputStream != null) {
      try {
        if (buffer != null && buffer.length > 0) {
          mOutputStream.write(buffer, 0, buffer.length);
          mOutputStream.flush();
          return true;
        }
      } catch (IOException e) {
        log.error("Can't sent data", e);
        throw e;
      }
    }

    return false;
  }

  @Override
  public byte[] readBytes(int byteCount) throws IOException {

    int readBytesReceived = 0;
    InputStream inputStream = GetInputStream(mSocket);
    if(inputStream == null || byteCount <= 0) {
      return new byte[]{};
    }
    byte[] readBuf = new byte[byteCount];
    readBytesReceived = inputStream.read(readBuf);

    return Arrays.copyOf(readBuf, readBytesReceived);
  }

  @Override
  public int getInputBufferBytesCount() throws IOException {
    InputStream inputStream = GetInputStream(mSocket);
    if(inputStream != null) {
      return inputStream.available();
    }

    return 0;
  }

  @Override
  public boolean openPort() throws IOException {

    SocketAddress address = new InetSocketAddress(ADDRESS, PORT);
    mSocket = new Socket();
    mSocket.connect(address, CONNECTION_TIMEOUT);

    return true;
  }

  @Override
  public boolean closePort() throws IOException {
    try {
      if (mSocket != null) {
        mSocket.close();
        mSocket = null;
      }

      if (mThreadReceiveData != null) {
        if (mThreadReceiveData.isAlive()) {
          mThreadReceiveData.interrupt();
          mThreadReceiveData = null;
        }
      }
    } catch (IOException e) {
      log.warn("Failed to close socket", e);
      return false;
    }

    return true;
  }

  @Override
  public String getPortName() {
    return "AutoX_TCP_Port";
  }
}
