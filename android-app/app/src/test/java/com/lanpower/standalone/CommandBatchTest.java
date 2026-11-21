package com.lanpower.standalone;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class CommandBatchTest {
    @Test public void slowMachineDoesNotDelayDispatchOrResultsOfHealthyMachines() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch healthyReported = new CountDownLatch(3);
        List<Integer> completion = new ArrayList<>();
        List<String> result = CommandBatch.run(Arrays.asList(0, 1, 2, 3), 4, target -> {
            started.countDown();
            try {
                if (!started.await(3, TimeUnit.SECONDS)) throw new AssertionError("Commands dispatched sequentially");
                if (target == 0 && !healthyReported.await(3, TimeUnit.SECONDS)) throw new AssertionError("Healthy results blocked by slow machine");
            } catch (InterruptedException error) { throw new RuntimeException(error); }
            return "pc-" + target;
        }, (target, text, count) -> {
            completion.add(target);
            if (target != 0) healthyReported.countDown();
        });
        assertEquals(4, result.size());
        assertEquals(Integer.valueOf(0), completion.get(3));
    }
}
