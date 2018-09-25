package com.visualthreat.testing.mock;

import com.visualthreat.platform.common.can.CANLogEntry;
import com.visualthreat.testing.ICANBus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.visualthreat.testing.mock.ByteUtils.bytesToLong;

@Slf4j
@Getter @Setter
public class MockedCANStream implements Runnable {
  private static final int MAX_SNIFF_INTERVAL = 1000;
  private static final Long DEFAULT_BYTES = 0L;
  private static final Random rnd = new SecureRandom();

  private final Map<Integer, Map<Long, Set<CANLogEntry>>> execMessages =
      new ConcurrentHashMap<>();
  private final AbstractList<CANLogEntry> sniffMessages = new Vector<>();
  private final int[] sniffIntervals = new int[MAX_SNIFF_INTERVAL];
  private final Queue<CANLogEntry> input = new ConcurrentLinkedQueue<>();
  private final Queue<CANLogEntry> output = new ConcurrentLinkedQueue<>();
  private final Collection<Thread> threads = new LinkedList<>();

  private final ICANBus canBus;
  private final boolean exactMatch;
  private final String SNIFF_PREFIX = "sniff-";
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  private boolean isStopped = false;
  private int inputOutputTimeout = 1; // ms
  private int sniffIntervalSize = 0;

  MockedCANStream(ICANBus canBus, String logsFolder, boolean exactMatch) {
    this.canBus = canBus;
    this.exactMatch = exactMatch;
    preStart(logsFolder);
  }

  MockedCANStream(ICANBus canBus, String logsFolder) {
    this(canBus, logsFolder, false);
  }

  public static void start(MockedCANStream canStream) {
    new Thread(canStream).start();
  }

  @Override
  public void run() {
    startExecCycle();
    startSniffCycle();
    listenForCancel();
    startOutput();

    Runtime.getRuntime().addShutdownHook(
        new Thread(() -> threads.forEach(Thread::interrupt)));
  }

  void executeFrame(CANLogEntry message) {
    input.offer(message);
  }

  private void startOutput() {
    Thread outputThread = new Thread(() -> {
      while (true) {
        try {
          Thread.sleep(inputOutputTimeout);
        } catch (InterruptedException e) {
          break;
        }
        if (!output.isEmpty()) {
          output.forEach(canBus::publishCANEvent);
          output.clear();
        }
      }
    });
    outputThread.start();
    threads.add(outputThread);
  }

  private void startExecCycle() {
    Thread execThread = new Thread(() -> {
      while (true) {
        if (input.isEmpty()) {
          try {
            Thread.sleep(inputOutputTimeout);
          } catch (InterruptedException e) {
            break;
          }
        } else {
          CANLogEntry message = input.poll();
          if (message != null) {
            handleExecMessage(message);
          }
        }
      }
    });
    execThread.start();
    threads.add(execThread);
  }

  private void handleExecMessage(CANLogEntry message) {
    long byteId = exactMatch ? bytesToLong(message.getData()) : DEFAULT_BYTES;
    Map<Long, Set<CANLogEntry>> idMessages = execMessages.getOrDefault(
        message.getId(), Collections.emptyMap());
    Set<CANLogEntry> answer = idMessages.getOrDefault(byteId, Collections.emptySet());
    for (CANLogEntry answerMsg : answer) {
      if (cancelled.compareAndSet(true, false)) {
        break;
      }

      output.offer(answerMsg);
    }
  }

  private void startSniffCycle() {
    if (sniffIntervalSize > 0) {
      int size = sniffMessages.size();
      Thread sniffThread = new Thread(() -> {
        while (true) {
          try {
            Thread.sleep(sniffInterval());
          } catch (InterruptedException e) {
            break;
          }

          CANLogEntry message = sniffMessages.get(rnd.nextInt(size));
          output.offer(message);
        }
      });
      sniffThread.start();
      threads.add(sniffThread);
    }
  }

  private void listenForCancel() {
    output.clear();
  }

  private int sniffInterval() {
    int currentSize = rnd.nextInt(sniffIntervalSize);
    int interval = 0;
    for (int size : sniffIntervals) {
      interval++;
      currentSize -= size;
      if (currentSize <= 0) {
        break;
      }
    }

    return interval;
  }

  private void preStart(String logsFolder) {
    new Thread(() -> {
      log.info("Starting parsing mock log files, takes 10-100 seconds, " +
          "depending on log files size");
      try {
        Map<Boolean, List<Path>> mockFiles =
            Files.list(Paths.get(logsFolder))
                .collect(Collectors.partitioningBy(f ->
                    f.getFileName().toString().startsWith(SNIFF_PREFIX)));
        Thread sniffThread = new Thread(() ->
            mockFiles.get(true).forEach(this::addSniffLogs));
        Thread execThread = new Thread(() ->
            mockFiles.get(false).forEach(this::addExecLogs));

        sniffThread.start();
        execThread.start();
        execThread.join();
        sniffThread.join();

        log.info("Total req/res pairs = {}:{}", execMessages.size(),
            execMessages.values().stream()
                .flatMap(map -> map.values().stream())
                .map(Collection::size)
                .reduce(0, (a, b) -> a + b));
        log.info("Total sniff messages: {}", sniffMessages.size());
      } catch (IOException e) {
        log.error("Can't open files with logs for mocking", e);
        System.exit(1);
      } catch (InterruptedException e) {
        log.error("Interruption while waiting parsing logs");
        System.exit(1);
      }
      log.info("Finished mock log files parsing");
    }).start();
  }

  private void addSniffLogs(Path fileName) {
    long prevTime = 0;
    try (Scanner scanner = new Scanner(fileName)) {
      for (int i = 0; i < MAX_SNIFF_INTERVAL; i++) {
        sniffIntervals[i] = 0;
      }
      while (scanner.hasNextLine()) {
        String line = scanner.nextLine();
        CANLogEntry canLogEntry = CANLogEntry.fromString(line);
        if (canLogEntry != null) {
          sniffMessages.add(canLogEntry);
          if (prevTime != 0) {
            int interval = (int) (canLogEntry.getTimeStamp() - prevTime);
            if (interval > 0 && interval < MAX_SNIFF_INTERVAL) {
              sniffIntervals[interval] += 1;
              sniffIntervalSize++;
            }
          }

          prevTime = canLogEntry.getTimeStamp();
        }
      }
    } catch (IOException e) {
      log.error("Can not read sniff log: {}",
          fileName.toAbsolutePath().toString());
      e.printStackTrace();
    }
  }

  private void addExecLogs(Path fileName) {
    try (Scanner scanner = new Scanner(fileName)) {
      int id = -1;
      ArrayList<CANLogEntry> msgList = new ArrayList<>();
      while (scanner.hasNextLine()) {
        String line = scanner.nextLine();
        CANLogEntry canLogEntry = CANLogEntry.fromString(line);
        if (canLogEntry != null) {
          if (canLogEntry.getType().equals(CANLogEntry.FrameType.request)) {
            saveExecMessage(id, msgList);
            msgList.clear();
            id = canLogEntry.getId();
          } else {
            msgList.add(canLogEntry);
          }
        }
      }

      saveExecMessage(id, msgList);
    } catch (IOException e) {
      log.error("Can not read exec log: {}",
          fileName.toAbsolutePath().toString());
      e.printStackTrace();
    }
  }

  private void saveExecMessage(int id, Collection<CANLogEntry> cmds) {
    if (id > 0) {
      Map<Long, Set<CANLogEntry>> messagesMap = execMessages.computeIfAbsent(id, (i) -> new HashMap<>());

      for (CANLogEntry cmd : cmds) {
        Long byteId = exactMatch ? bytesToLong(cmd.getData()) : DEFAULT_BYTES;
        Set<CANLogEntry> messages = messagesMap.computeIfAbsent(byteId, (i) -> new HashSet<>());
        messages.add(cmd);
      }
    }
  }
}
