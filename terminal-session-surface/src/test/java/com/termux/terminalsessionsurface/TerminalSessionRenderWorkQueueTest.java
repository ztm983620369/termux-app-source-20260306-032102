package com.termux.terminalsessionsurface;

import org.junit.Assert;
import org.junit.Test;

public class TerminalSessionRenderWorkQueueTest {

    @Test
    public void repeatedUpdatesCollapseWithoutChangingFifoOrder() {
        TerminalSessionRenderWorkQueue<Object> queue = new TerminalSessionRenderWorkQueue<>();
        Object first = new Object();
        Object second = new Object();

        Assert.assertTrue(queue.offer(first));
        Assert.assertFalse(queue.offer(first));
        Assert.assertTrue(queue.offer(second));
        Assert.assertEquals(2, queue.size());
        Assert.assertSame(first, queue.poll());
        Assert.assertSame(second, queue.poll());
        Assert.assertTrue(queue.isEmpty());
    }

    @Test
    public void equalObjectsRemainIndependentRenderOwners() {
        TerminalSessionRenderWorkQueue<String> queue = new TerminalSessionRenderWorkQueue<>();
        String first = new String("session");
        String second = new String("session");

        Assert.assertTrue(queue.offer(first));
        Assert.assertTrue(queue.offer(second));
        Assert.assertSame(first, queue.poll());
        Assert.assertSame(second, queue.poll());
    }

    @Test
    public void removalAllowsOwnerToBeScheduledAgain() {
        TerminalSessionRenderWorkQueue<Object> queue = new TerminalSessionRenderWorkQueue<>();
        Object owner = new Object();

        Assert.assertTrue(queue.offer(owner));
        Assert.assertTrue(queue.remove(owner));
        Assert.assertTrue(queue.offer(owner));
        Assert.assertSame(owner, queue.poll());
    }
}
