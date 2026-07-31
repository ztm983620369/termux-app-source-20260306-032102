package com.termux.view;

import org.junit.Assert;
import org.junit.Test;

public class TerminalGeometryCommitPolicyTest {

    private static TerminalGeometryCommitPolicy.Geometry geometry(int columns, int rows) {
        return new TerminalGeometryCommitPolicy.Geometry(columns, rows, 10, 20);
    }

    @Test
    public void imeAnimationNeverCommitsIntermediateRows() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        TerminalGeometryCommitPolicy.Geometry baseline = geometry(80, 30);
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.COMMIT,
            policy.request(baseline, TerminalGeometryCommitPolicy.Source.INITIAL_ATTACH));
        policy.markCommitted(baseline);

        policy.setImeViewportActive(true);
        for (int rows = 29; rows >= 12; rows--) {
            Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.SUPPRESSED_BY_IME,
                policy.request(geometry(80, rows), TerminalGeometryCommitPolicy.Source.LAYOUT));
        }
        Assert.assertTrue(policy.matchesCommitted(80, 30, 10, 20));
        Assert.assertEquals(18L, policy.getSuppressedByImeCount());

        policy.setImeViewportActive(false);
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.UNCHANGED,
            policy.request(baseline, TerminalGeometryCommitPolicy.Source.LAYOUT));
    }

    @Test
    public void transientStructuralSizeThatReturnsToBaselineNeverCommits() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        TerminalGeometryCommitPolicy.Geometry baseline = geometry(80, 30);
        policy.markCommitted(baseline);

        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.request(geometry(80, 20), TerminalGeometryCommitPolicy.Source.LAYOUT));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.UNCHANGED,
            policy.request(baseline, TerminalGeometryCommitPolicy.Source.LAYOUT));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.UNCHANGED,
            policy.onVsync(baseline));
    }

    @Test
    public void realStructuralChangeCommitsAfterTwoMatchingFrames() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        TerminalGeometryCommitPolicy.Geometry baseline = geometry(80, 30);
        TerminalGeometryCommitPolicy.Geometry rotated = geometry(46, 52);
        policy.markCommitted(baseline);

        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.request(rotated, TerminalGeometryCommitPolicy.Source.STRUCTURAL));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.onVsync(rotated));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.COMMIT,
            policy.onVsync(rotated));
        policy.markCommitted(rotated);
        Assert.assertTrue(policy.matchesCommitted(46, 52, 10, 20));
    }

    @Test
    public void explicitTextScaleRemainsImmediateDuringIme() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        policy.markCommitted(geometry(80, 30));
        policy.setImeViewportActive(true);

        TerminalGeometryCommitPolicy.Geometry scaled =
            new TerminalGeometryCommitPolicy.Geometry(64, 24, 12, 24);
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.COMMIT,
            policy.request(scaled, TerminalGeometryCommitPolicy.Source.USER_TEXT_SCALE));
    }

    @Test
    public void renderBarriersCannotBypassImeOwnership() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        policy.markCommitted(geometry(80, 30));
        policy.setImeViewportActive(true);

        for (int index = 0; index < 100; index++) {
            Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.SUPPRESSED_BY_IME,
                policy.request(geometry(80, 14),
                    TerminalGeometryCommitPolicy.Source.RENDER_BARRIER));
        }
        Assert.assertTrue(policy.matchesCommitted(80, 30, 10, 20));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.UNCHANGED,
            policy.onVsync(geometry(80, 14)));
    }

    @Test
    public void changingLayoutCandidateRestartsStabilityProof() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        policy.markCommitted(geometry(80, 30));

        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.request(geometry(81, 30), TerminalGeometryCommitPolicy.Source.LAYOUT));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.onVsync(geometry(82, 30)));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.onVsync(geometry(82, 30)));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.COMMIT,
            policy.onVsync(geometry(82, 30)));
    }

    @Test
    public void explicitStructuralTransactionCanCommitWhileImeRemainsVisible() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        policy.markCommitted(geometry(80, 30));
        policy.setImeViewportActive(true);
        TerminalGeometryCommitPolicy.Geometry rotated = geometry(46, 52);

        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.request(rotated, TerminalGeometryCommitPolicy.Source.STRUCTURAL));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.WAIT_FOR_STABLE_FRAME,
            policy.onVsync(rotated));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.COMMIT,
            policy.onVsync(rotated));
    }

    @Test
    public void settledImeEndpointNeverChangesTheTerminalGrid() {
        TerminalGeometryCommitPolicy policy = new TerminalGeometryCommitPolicy();
        policy.markCommitted(geometry(80, 44));
        policy.setImeViewportActive(true);
        TerminalGeometryCommitPolicy.Geometry settled = geometry(80, 25);

        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.SUPPRESSED_BY_IME,
            policy.request(settled, TerminalGeometryCommitPolicy.Source.LAYOUT));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.SUPPRESSED_BY_IME,
            policy.request(settled, TerminalGeometryCommitPolicy.Source.RENDER_BARRIER));
        Assert.assertEquals(TerminalGeometryCommitPolicy.Decision.UNCHANGED,
            policy.onVsync(settled));
        Assert.assertTrue(policy.matchesCommitted(80, 44, 10, 20));
    }
}
