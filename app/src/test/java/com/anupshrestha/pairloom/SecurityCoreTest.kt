package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.security.AuthStatus
import com.anupshrestha.pairloom.security.Fingerprints
import com.anupshrestha.pairloom.security.InstallId
import com.anupshrestha.pairloom.security.PairedDevice
import com.anupshrestha.pairloom.security.PairedDeviceStore
import com.anupshrestha.pairloom.security.PairingCrypto
import com.anupshrestha.pairloom.security.PeerAuthenticator
import com.anupshrestha.pairloom.security.PeerTrustManager
import com.anupshrestha.pairloom.security.TrustStoreException
import com.anupshrestha.pairloom.security.hexToBytesOrNull
import com.anupshrestha.pairloom.security.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.anupshrestha.pairloom.p2p.GroupCredentialStore
import com.anupshrestha.pairloom.p2p.GroupCredentials
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.SecureRandom
import java.security.SignatureException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.Random
import javax.security.auth.x500.X500Principal

class SecurityCoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rng = Random(42)
    private fun bytes(n: Int) = ByteArray(n).also(rng::nextBytes)

    // --- fingerprints and hex --------------------------------------------------------------

    @Test
    fun hexRoundTripsAndRejectsBadInput() {
        val b = bytes(16)
        assertArrayEquals(b, hexToBytesOrNull(b.toHex(), 16))
        assertArrayEquals(b, hexToBytesOrNull(b.toHex().uppercase(), 16))
        assertNull(hexToBytesOrNull(b.toHex(), 32))
        assertNull(hexToBytesOrNull("zz".repeat(16), 16))
        assertEquals("ABCD 0123 4567 89EF", Fingerprints.short("abcd0123456789ef" + "0".repeat(48)))
    }

    @Test
    fun p256IsRecognisedAndOtherKeysAreNot() {
        assertTrue(Fingerprints.isP256(ecKey("secp256r1")))
        assertFalse(Fingerprints.isP256(ecKey("secp384r1")))
        assertFalse(Fingerprints.isP256(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public))
        assertEquals(32, Fingerprints.of(ecKey("secp256r1")).size)
    }

    // --- pairing crypto ----------------------------------------------------------------------

    @Test
    fun commitmentBindsBothKeysAndTheNonce() {
        val fs = bytes(32); val fc = bytes(32); val ns = bytes(32)
        val c = PairingCrypto.commitment(fs, fc, ns)
        assertTrue(PairingCrypto.commitmentMatches(c, fs, fc, ns))
        assertFalse(PairingCrypto.commitmentMatches(c, fs, fc, bytes(32)))
        assertFalse(PairingCrypto.commitmentMatches(c, bytes(32), fc, ns))
        assertFalse(PairingCrypto.commitmentMatches(c, fs, bytes(32), ns))
        assertFalse(PairingCrypto.commitmentMatches(c, fc, fs, ns)) // order matters
    }

    @Test
    fun codeIsSixDigitsDeterministicAndDependsOnEveryInput() {
        val fs = bytes(32); val fc = bytes(32); val ns = bytes(32); val nc = bytes(32)
        val code = PairingCrypto.code(fs, fc, ns, nc)
        assertTrue(code.matches(Regex("\\d{6}")))
        assertEquals(code, PairingCrypto.code(fs.copyOf(), fc.copyOf(), ns.copyOf(), nc.copyOf()))
        assertEquals(code.take(3) + " " + code.drop(3), PairingCrypto.display(code))
        // Changing any input changes the code (with overwhelming probability; fixed seed here).
        assertNotEquals(code, PairingCrypto.code(bytes(32), fc, ns, nc))
        assertNotEquals(code, PairingCrypto.code(fs, bytes(32), ns, nc))
        assertNotEquals(code, PairingCrypto.code(fs, fc, bytes(32), nc))
        assertNotEquals(code, PairingCrypto.code(fs, fc, ns, bytes(32)))
    }

    @Test
    fun aManInTheMiddleWithItsOwnKeysAlmostNeverMatchesBothCodes() {
        // Each victim computes the code over the keys it saw: A sees (A, M1), B sees (M2, B).
        var matches = 0
        repeat(2000) {
            val a = bytes(32); val b = bytes(32); val m1 = bytes(32); val m2 = bytes(32)
            val codeA = PairingCrypto.code(a, m1, bytes(32), bytes(32))
            val codeB = PairingCrypto.code(m2, b, bytes(32), bytes(32))
            if (codeA == codeB) matches++
        }
        assertTrue("matches=$matches", matches <= 1) // expected about 0.002
    }

    @Test
    fun wrongSizedInputsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { PairingCrypto.commitment(bytes(31), bytes(32), bytes(32)) }
        assertThrows(IllegalArgumentException::class.java) { PairingCrypto.code(bytes(32), bytes(32), bytes(32), bytes(16)) }
    }

    // --- peer classification -----------------------------------------------------------------

    private val own = "a".repeat(64)
    private val ownId = "1".repeat(32)
    private val peerFp = "b".repeat(64)
    private val peerId = "2".repeat(32)
    private val record = PairedDevice(peerFp, peerId, "Peer", 1)

    private fun decide(fp: String, id: String, paired: List<PairedDevice>) = PeerAuthenticator.decide(own, fp, id, ownId, paired)

    @Test
    fun knownKeyAndIdAreAuthenticated() {
        val d = decide(peerFp, peerId, listOf(record))
        assertEquals(AuthStatus.AUTHENTICATED, d.status)
        assertEquals(record, d.record)
    }

    @Test
    fun unknownPhoneNeedsPairing() {
        assertEquals(AuthStatus.NEEDS_PAIRING, decide(peerFp, peerId, emptyList()).status)
        assertEquals(AuthStatus.NEEDS_PAIRING, decide("c".repeat(64), "3".repeat(32), listOf(record)).status)
    }

    @Test
    fun changedKeyOrIdIsAHardStop() {
        assertEquals(AuthStatus.CONFLICT, decide("c".repeat(64), peerId, listOf(record)).status) // same install, new key
        assertEquals(AuthStatus.CONFLICT, decide(peerFp, "3".repeat(32), listOf(record)).status) // same key, new install ID
        assertEquals(AuthStatus.CONFLICT, decide(own, peerId, emptyList()).status) // our own key reflected
        assertEquals(AuthStatus.CONFLICT, decide(peerFp, ownId, emptyList()).status) // our own install ID reflected
    }

    @Test
    fun statusCodesRoundTrip() {
        for (s in AuthStatus.entries) assertEquals(s, AuthStatus.fromCode(s.code))
        assertNull(AuthStatus.fromCode(0))
        assertNull(AuthStatus.fromCode(99))
    }

    // --- paired-device store -----------------------------------------------------------------

    @Test
    fun storeRoundTripsReplacesAndForgets() {
        val store = PairedDeviceStore(tmp.newFolder().resolve("paired"))
        assertTrue(store.load().isEmpty())
        val a = PairedDevice("a".repeat(64), "1".repeat(32), "Phone\twith tab 電話", 10)
        val b = PairedDevice("b".repeat(64), "2".repeat(32), "", 20)
        store.add(a)
        store.add(b)
        assertEquals(listOf(a, b), store.load())
        // Re-pairing the same install with a new key replaces the old record.
        val a2 = a.copy(fingerprintHex = "c".repeat(64), pairedAtMs = 30)
        store.add(a2)
        assertEquals(setOf(b, a2), store.load().toSet())
        assertTrue(store.remove(b.fingerprintHex))
        assertFalse(store.remove(b.fingerprintHex))
        assertEquals(listOf(a2), store.load())
    }

    @Test
    fun storeWritesAtomicallyWithoutLeftovers() {
        val dir = tmp.newFolder()
        val store = PairedDeviceStore(dir.resolve("paired"))
        repeat(5) { store.add(PairedDevice("%064x".format(it), "%032x".format(it), "n$it", it.toLong())) }
        assertEquals(listOf("paired"), dir.list()!!.toList())
        assertEquals(5, store.load().size)
    }

    @Test
    fun corruptStoreFailsClosed() {
        val dir = tmp.newFolder()
        val file = dir.resolve("paired")
        val bad = listOf(
            "something else\n",
            "${PairedDeviceStore.HEADER_V1}\nnot\tenough\n",
            "${PairedDeviceStore.HEADER_V1}\n${"z".repeat(64)}\t${"1".repeat(32)}\tbmFtZQ\t1\n",
            "${PairedDeviceStore.HEADER_V1}\n${"a".repeat(64)}\t${"1".repeat(31)}\tbmFtZQ\t1\n",
            "${PairedDeviceStore.HEADER_V1}\n${"a".repeat(64)}\t${"1".repeat(32)}\t!!!\t1\n",
            "${PairedDeviceStore.HEADER_V1}\n${"a".repeat(64)}\t${"1".repeat(32)}\tbmFtZQ\tsoon\n",
            "${PairedDeviceStore.HEADER_V1}\n${"a".repeat(64)}\t${"1".repeat(32)}\tbmFtZQ\t1\n${"a".repeat(64)}\t${"2".repeat(32)}\tbmFtZQ\t1\n",
        )
        for (text in bad) {
            file.writeText(text)
            assertThrows(text, TrustStoreException::class.java) { PairedDeviceStore(file).load() }
        }
    }

    @Test
    fun installIdIsCreatedOnceAndRejectsCorruption() {
        val file = tmp.newFolder().resolve("install-id")
        val id = InstallId.getOrCreate(file, SecureRandom())
        assertEquals(32, id.length)
        assertEquals(id, InstallId.getOrCreate(file, SecureRandom()))
        file.writeText("nonsense")
        assertThrows(java.io.IOException::class.java) { InstallId.getOrCreate(file) }
    }

    // --- trust manager -------------------------------------------------------------------------

    private fun ecKey(curve: String): PublicKey =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair().public

    private class StubCert(private val key: PublicKey, private val selfSigned: Boolean = true) : X509Certificate() {
        override fun getPublicKey(): PublicKey = key
        override fun checkValidity() {}
        override fun checkValidity(date: Date?) {}
        override fun getVersion() = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        @Deprecated("required override") override fun getIssuerDN(): Principal = X500Principal("CN=stub")
        @Deprecated("required override") override fun getSubjectDN(): Principal = X500Principal("CN=stub")
        override fun getNotBefore() = Date(0)
        override fun getNotAfter() = Date(Long.MAX_VALUE)
        override fun getTBSCertificate() = ByteArray(0)
        override fun getSignature() = ByteArray(0)
        override fun getSigAlgName() = "SHA256withECDSA"
        override fun getSigAlgOID() = "1.2.840.10045.4.3.2"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints() = -1
        override fun getEncoded() = ByteArray(0)
        override fun verify(key: PublicKey?) { if (!selfSigned) throw SignatureException("bad signature") }
        override fun verify(key: PublicKey?, sigProvider: String?) = verify(key)
        override fun toString() = "StubCert"
        override fun hasUnsupportedCriticalExtension() = false
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }

    @Test
    fun trustManagerAcceptsOneSelfSignedP256CertificateAndRecordsIt() {
        val key = ecKey("secp256r1")
        val tm = PeerTrustManager()
        tm.checkServerTrusted(arrayOf(StubCert(key)), "ECDHE_ECDSA", null as java.net.Socket?)
        assertArrayEquals(Fingerprints.of(key), tm.acceptedFingerprint())
        assertEquals(1, tm.calls.get())
    }

    @Test
    fun trustManagerRejectsAnythingElse() {
        val p256 = ecKey("secp256r1")
        val cases = listOf(
            emptyArray<X509Certificate>(),
            arrayOf<X509Certificate>(StubCert(p256), StubCert(p256)),
            arrayOf<X509Certificate>(StubCert(ecKey("secp384r1"))),
            arrayOf<X509Certificate>(StubCert(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public)),
            arrayOf<X509Certificate>(StubCert(p256, selfSigned = false)),
        )
        for (chain in cases) {
            val tm = PeerTrustManager()
            assertThrows(CertificateException::class.java) { tm.checkClientTrusted(chain, "EC", null as java.net.Socket?) }
            assertNull(tm.acceptedFingerprint())
        }
        val tm = PeerTrustManager()
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(null, "EC") }
    }

    // --- paired phones' groups (direct join) ----------------------------------------------------

    private val group = GroupCredentials("DIRECT-ab-Pairloom", "Abcdefgh12345678")

    @Test
    fun pairedGroupsAreStoredAndVersionOneFilesStillLoad() {
        val file = tmp.newFolder().resolve("paired")
        val fp = "a".repeat(64)
        // A version-1 file (no group columns) from an older build.
        file.writeText("${PairedDeviceStore.HEADER_V1}\n$fp\t${"1".repeat(32)}\tbmFtZQ\t5\n")
        val store = PairedDeviceStore(file)
        assertEquals(null, store.load().single().groupName)
        assertTrue(store.updateGroup(fp, group.networkName, group.passphrase))
        assertFalse(store.updateGroup("b".repeat(64), group.networkName, group.passphrase)) // not paired
        val d = PairedDeviceStore(file).load().single()
        assertEquals("name", d.name)
        assertEquals(group.networkName, d.groupName)
        assertEquals(group.passphrase, d.groupPassphrase)
        assertTrue(file.readText().startsWith(PairedDeviceStore.HEADER + "\n"))
        assertFalse("passphrase must not appear in toString", d.toString().contains(group.passphrase))
    }

    @Test
    fun aDamagedGroupColumnFailsClosed() {
        val file = tmp.newFolder().resolve("paired")
        val row = "${"a".repeat(64)}\t${"1".repeat(32)}\tbmFtZQ\t5"
        for (cols in listOf("DIRECT-ab-Other\tAbcdefgh12345678", "DIRECT-ab-Pairloom\tshort", "DIRECT-ab-Pairloom\t-", "-")) {
            file.writeText("${PairedDeviceStore.HEADER}\n$row\t$cols\n")
            assertThrows(cols, TrustStoreException::class.java) { PairedDeviceStore(file).load() }
        }
    }

    @Test
    fun ownGroupCredentialsPersistRotateAndRecoverFromDamage() {
        val file = tmp.newFolder().resolve("group")
        val store = GroupCredentialStore(file)
        val first = store.get()
        assertTrue(GroupCredentials.isValid(first.networkName, first.passphrase))
        assertEquals(first.passphrase, GroupCredentialStore(file).get().passphrase) // kept across restarts
        val rotated = store.rotate()
        assertNotEquals(first.passphrase, rotated.passphrase)
        assertEquals(rotated.passphrase, GroupCredentialStore(file).get().passphrase)
        file.writeText("garbage")
        val fresh = GroupCredentialStore(file).get()
        assertTrue(GroupCredentials.isValid(fresh.networkName, fresh.passphrase))
    }

    @Test
    fun onlyPairloomGroupsAreValid() {
        assertTrue(GroupCredentials.isValid("DIRECT-x9-Pairloom", "abcdefgh"))
        assertFalse(GroupCredentials.isValid("DIRECT-x9-Printer", "abcdefgh"))
        assertFalse(GroupCredentials.isValid("DIRECT-x9-Pairloom", "abc def gh"))
        assertFalse(GroupCredentials.isValid("DIRECT-x9-Pairloom", "a".repeat(64)))
    }
}
