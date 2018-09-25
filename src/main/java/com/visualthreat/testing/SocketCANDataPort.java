package com.visualthreat.testing;

import com.google.common.base.Charsets;

import de.entropia.can.CanSocket;

import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

/**
 * Supports to write / read data to / from a SocketCAN data port
 */
@Slf4j
public class SocketCANDataPort implements ICANDataPort {
  private CanSocket canSocket;
  private CanSocket.CanInterface canInterface;
  private String canIf;

  private Thread mThreadReceiveData;
  private IRAWDataListener dataListener;
  private volatile boolean isClosed = false;
  private String baudRateStr = null;

  public SocketCANDataPort(String socketCANPort, IRAWDataListener listener) throws IOException {
    this.dataListener = listener;
    this.canIf = socketCANPort;
    openPort();
  }

  /**
   * Thread receive RAW CAN data from SocketCAN
   */
  public void startReadingMessages() {
    Runnable runnable = new Runnable() {
      @Override
      public void run() {

        while (canSocket!=null && !isClosed) {
          try {
            if(dataListener != null && baudRateStr != null){
              dataListener.readData(baudRateStr.getBytes(Charsets.US_ASCII));
              baudRateStr = null;
            }
            String rawCANData = canSocket.recvS();
            if (rawCANData != null && rawCANData.length() > 0) {
              if(dataListener != null){
                dataListener.readData(rawCANData.getBytes(Charsets.US_ASCII));
              }
            } else {
              try {
                Thread.sleep(1);
              } catch (InterruptedException e) {
                return;
              }
            }
          } catch (IOException e) {
            if(!isClosed) {
              log.error("SocketCAN Reader exiting due to errors", e);
            } else if (e.getMessage().contains("Resource temporarily unavailable")) {
              //ignored due to timeout error
            } else {
              return;
            }
          }
        }
        log.info("SocketCAN Reader exited");
      }
    };
    mThreadReceiveData = new Thread(runnable);
    mThreadReceiveData.start();
  }

  @Override
  public boolean writeBytes(byte[] buffer) throws IOException {
    if (this.canSocket != null) {
      if (buffer != null && buffer.length > 0) {
        if(buffer[0] == 'S'){
          switch (buffer[1]){
            case '0':
              this.setBaudRate(10000);
              break;
            case '1':
              this.setBaudRate(20000);
              break;
            case '2':
              this.setBaudRate(50000);
              break;
            case '3':
              this.setBaudRate(100000);
              break;
            case '4':
              this.setBaudRate(125000);
              break;
            case '5':
              this.setBaudRate(250000);
              break;
            case '6':
              this.setBaudRate(500000);
              break;
            case '7':
              this.setBaudRate(750000);
              break;
            case '8':
              this.canSocket.setBitrate(this.canIf, 1000000);
              break;
            default:
              return true;
          }
        } else if (buffer[0] == 'I') {
          int baudRate = this.canSocket.getBitrate(this.canIf);
          baudRateStr = String.format("I%d\r", baudRate);
        } else if (buffer[0] == 'W') {
          closePort();
          CanSocket.autoDetectBaudRate(this.canIf);
          openPort();
        } else {
          this.canSocket.sendS(this.canInterface, new String(buffer, Charsets.US_ASCII));
        }
      }
      return true;
    }

    return false;
  }

  private void setBaudRate(int newBaudRate) throws IOException {
    this.canSocket.setBitrate(canInterface.getIfName(), newBaudRate);
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    closePort();
    openPort();
  }

  @Override
  public byte[] readBytes(int byteCount) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getInputBufferBytesCount() throws IOException {
    // always mark that there are some data
    return 1;
  }

  @Override
  public boolean openPort() throws IOException {
    if(this.canSocket != null){
      closePort();
    }
    this.isClosed = false;
    canSocket = new CanSocket(CanSocket.Mode.RAW);
    canSocket.setSNDTimeOut(3000);
    canSocket.setRCVTimeOut(60000);
    canInterface = new CanSocket.CanInterface(canSocket, this.canIf);
    canSocket.bind(canInterface);
    startReadingMessages();
    return true;
  }

  @Override
  public boolean closePort() throws IOException {
    this.isClosed = true;
    int totalWaitTime = 1500;
    int stepWaitTime = 100;
    do {
      try {
        // let message read loop exit
        Thread.sleep(stepWaitTime);
        totalWaitTime -= stepWaitTime;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    } while(mThreadReceiveData.isAlive() && totalWaitTime > 0);

    if (canSocket != null) {
      canSocket.close();
      canSocket = null;
    }

    if (mThreadReceiveData != null) {
      if (mThreadReceiveData.isAlive()) {
        mThreadReceiveData.interrupt();
        mThreadReceiveData = null;
      }
    }
    return true;
  }

  @Override
  public String getPortName() {
    return "SocketCAN";
  }
}
