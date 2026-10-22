package com.thunder.agi

import com.thunder.agi.ledger.Transaction
import com.thunder.agi.ledger.TxBundle
import com.thunder.agi.ledger.TxText
import com.thunder.agi.ledger.TxWire
import com.thunder.agi.ledger.WalletAddress
import com.thunder.agi.p2p.BeaconCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class AddressAndWireTest {
    private val rnd = SecureRandom()
    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    @Test fun addressesAlwaysStartWithSAndHave34Chars() {
        repeat(1000) {
            val a = WalletAddress.fromPayload(ByteArray(20).also { rnd.nextBytes(it) })
            assertEquals(34, a.length); assertEquals('S', a[0]); assertTrue(WalletAddress.isValid(a))
        }
    }

    @Test fun checksumCatchesEveryTypo() {
        repeat(100) {
            val a = WalletAddress.fromPayload(ByteArray(20).also { rnd.nextBytes(it) })
            for (pos in a.indices) {
                val c = alphabet.filter { it != a[pos] }[rnd.nextInt(alphabet.length - 1)]
                val typo = a.substring(0, pos) + c + a.substring(pos + 1)
                assertFalse("typo at $pos must be rejected: $typo", WalletAddress.isValid(typo))
            }
        }
    }

    @Test fun garbageAndForeignAddressesAreRejected() {
        for (s in listOf("", "S", "hello", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", "TJRabPrwbZy45sbavfcjinPJC18kjpRTv8", "0x52908400098527886E0F7030069857D2E4169EE7"))
            assertFalse(s, WalletAddress.isValid(s))
    }

    @Test fun addressCommitsToBothKeys() {
        val w = TestWallet()
        assertTrue(WalletAddress.matches(w.address, w.identity.publicKey, w.kyberHash))
        val other = TestWallet()
        assertFalse(WalletAddress.matches(w.address, other.identity.publicKey, w.kyberHash))
        assertFalse(WalletAddress.matches(w.address, w.identity.publicKey, other.kyberHash))
    }

    @Test fun transactionFitsTheTinyChannels() {
        val a = TestWallet(); val b = TestWallet()
        val bare = a.craft(b.address, 5_000, 1, null)
        assertEquals(178, bare.size)
        val withMemo = a.craft(b.address, 5_000, 1, null, memo = "x".repeat(48))
        assertEquals(226, withMemo.size)
        assertTrue("must fit a 255-byte WiFi-Aware message with the beacon header", BeaconCodec.encodeTx(withMemo, 0).size <= 255)
        assertTrue("TX1: text must fit comfortably in one WhatsApp message", TxText.encode(withMemo).length < 400)
    }

    @Test fun wireRoundTripKeepsEveryField() {
        val a = TestWallet(); val b = TestWallet()
        val raw = a.craft(b.address, 123_456_789, 77, "ab".repeat(32), memo = "rent ✓ شكرا", tsSec = 1_800_000_000)
        val tx = TxWire.decode(raw)!!
        assertEquals(a.address, tx.from); assertEquals(b.address, tx.to)
        assertEquals(123_456_789L, tx.amountMilli); assertEquals(77L, tx.nonce); assertEquals(1_800_000_000L, tx.timestampSec)
        assertEquals("rent ✓ شكرا", tx.memo); assertArrayEquals(ByteArray(8) { 0xAB.toByte() }, tx.prevId8)
        assertArrayEquals(a.identity.publicKey, tx.senderPubKey); assertArrayEquals(a.kyberHash, tx.senderKyberHash)
        assertEquals(TxWire.txidOf(tx.body), tx.txid)
        assertEquals(Transaction.Type.TRANSFER, tx.type)
    }

    @Test fun malformedWireIsRejected() {
        val a = TestWallet(); val b = TestWallet()
        val raw = a.craft(b.address, 1000, 1, null, memo = "hi")
        assertNotNull(TxWire.decode(raw))
        assertNull(TxWire.decode(raw.copyOf(raw.size - 1)))
        assertNull(TxWire.decode(raw + byteArrayOf(0)))
        assertNull(TxWire.decode(ByteArray(0)))
        assertNull(TxWire.decode(raw.clone().also { it[0] = 9 }))                 // bad version
        assertNull(TxWire.decode(raw.clone().also { it[1] = 7 }))                 // bad type
        assertNull(TxWire.decode(raw.clone().also { it[64] = 49 }))               // memoLen > 48
        assertNull(TxWire.decode(raw.clone().also { it[65] = 0xFF.toByte() }))    // invalid UTF-8 in memo
        val zeroAmount = raw.clone().also { for (i in 42..47) it[i] = 0 }
        assertNull(TxWire.decode(zeroAmount))
        repeat(300) { assertNull(TxWire.decode(ByteArray(178 + rnd.nextInt(48)).also { rnd.nextBytes(it) }.also { it[0] = 2 })) }
    }

    @Test fun textArmorRoundTripsAndFindsSeveralInsideNoise() {
        val a = TestWallet(); val b = TestWallet()
        val r1 = a.craft(b.address, 1000, 1, null)
        val r2 = a.craft(b.address, 2000, 2, TxWire.decode(r1)!!.txid, memo = "second")
        val msg = "hey, here is the money ${TxText.encode(r1)} and also\n${TxText.encode(r2)} thanks!!"
        val found = TxText.extract(msg)
        assertEquals(2, found.size); assertArrayEquals(r1, found[0]); assertArrayEquals(r2, found[1])
        assertTrue(TxText.extract("TX1:AAAA").isEmpty())
        assertTrue(TxText.extract("no transaction here").isEmpty())
    }

    @Test fun bundleRoundTripsAndSurvivesTruncation() {
        val a = TestWallet(); val b = TestWallet()
        val list = (1..10).map { a.craft(b.address, 1000L * it, it.toLong(), null) }
        val bytes = TxBundle.encode(list)
        assertEquals(10, TxBundle.decode(bytes).size)
        assertEquals(9, TxBundle.decode(bytes.copyOf(bytes.size - 5)).size)
    }

    @Test fun beaconFramesRoundTrip() {
        val a = TestWallet(); val b = TestWallet()
        val raw = a.craft(b.address, 1500, 1, null)
        val f = BeaconCodec.decode(BeaconCodec.encodeTx(raw, 2)) as BeaconCodec.Frame.Tx
        assertArrayEquals(raw, f.raw); assertEquals(2, f.hops)
        val tx = TxWire.decode(raw)!!
        val notice = BeaconCodec.decode(BeaconCodec.encodeNotice(tx)) as BeaconCodec.Frame.Notice
        assertEquals(1500L, notice.amountMilli)
        assertTrue(BeaconCodec.noticeIsFor(notice, b.address)); assertFalse(BeaconCodec.noticeIsFor(notice, a.address))
        assertEquals(17, BeaconCodec.encodeNotice(tx).size)
        assertNull(BeaconCodec.decode(byteArrayOf(1, 2, 3))); assertNull(BeaconCodec.decode(ByteArray(0)))
        assertNull(BeaconCodec.decode(byteArrayOf('T'.code.toByte(), 'X'.code.toByte(), 0, 1, 2, 3)))
    }
}
