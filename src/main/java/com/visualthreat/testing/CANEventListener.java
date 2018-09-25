package com.visualthreat.testing;


import com.visualthreat.platform.common.can.CANLogEntry;

public interface CANEventListener {
  public void onEvent(CANLogEntry event);
}
