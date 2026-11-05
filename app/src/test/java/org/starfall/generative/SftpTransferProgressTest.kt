package org.starfall.generative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.starfall.generative.integrations.remote.sftpTransferPercent

class SftpTransferProgressTest {
    @Test fun bytesAreReportedAsClampedPercentages() {
        assertEquals(0, sftpTransferPercent(0, 100))
        assertEquals(25, sftpTransferPercent(25, 100))
        assertEquals(100, sftpTransferPercent(100, 100))
        assertEquals(100, sftpTransferPercent(150, 100))
        assertEquals(0, sftpTransferPercent(-5, 100))
    }

    @Test fun unknownSizesRemainIndeterminateAndLargeSizesDoNotOverflow() {
        assertNull(sftpTransferPercent(200, -1))
        assertNull(sftpTransferPercent(0, 0))
        assertEquals(50, sftpTransferPercent(Long.MAX_VALUE / 2, Long.MAX_VALUE))
    }
}
