package com.tencent.shadow.sample.host;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class ShadowUiSmokeRunnerTest {

    @Test
    public void validatesClosedBoundedSmokeProtocol() throws Exception {
        ShadowUiSmokeRunner.validateSpecification(
                "{\"schemaVersion\":1,\"steps\":["
                        + "{\"action\":\"assertDisplayed\",\"view\":\"@id/title\"},"
                        + "{\"action\":\"scroll\",\"view\":\"list\",\"dy\":200}]}"
        );
        assertThrows(IllegalArgumentException.class, () ->
                ShadowUiSmokeRunner.validateSpecification(
                        "{\"schemaVersion\":1,\"steps\":["
                                + "{\"action\":\"assertText\",\"view\":\"title\"}]}"
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                ShadowUiSmokeRunner.validateSpecification(
                        "{\"schemaVersion\":1,\"steps\":["
                                + "{\"action\":\"wait\",\"waitMs\":5000},"
                                + "{\"action\":\"wait\",\"waitMs\":5000},"
                                + "{\"action\":\"wait\",\"waitMs\":1}]}"
                )
        );
    }
}
