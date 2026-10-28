package personal.wallpaper.protocol.lock;

import org.junit.Test;
import static org.junit.Assert.*;

/** 真协议模型的正常、身份、时效与幂等反例；不冒远程Binder或目标机证据。 */
public final class LockProtocolTest {
    @Test public void inputOnlyRequiresBothOptInAndCurrentModeWithoutClaimingRenderReady() {
        String ticket=LockProtocol.sceneTicket("00000000-0000-0000-0000-000000000094","00000000-0000-0000-0000-000000000001");
        for(int mask=0;mask<16;mask++){boolean n=(mask&1)!=0,p=(mask&2)!=0,current=(mask&4)!=0,ready=(mask&8)!=0;assertEquals(n&&p&&current&&!ready,LockProtocol.priorityInputOnlyAllowed(n,p,current,ready,ticket,"BUSY_EDIT"));}
        assertFalse(LockProtocol.priorityInputOnlyAllowed(true,true,true,false,"legacy-ticket","BUSY_EDIT"));
        assertFalse(LockProtocol.priorityInputOnlyAllowed(true,true,true,false,ticket,"NO_ENGINE"));
    }
    @Test public void sceneIdentitySurvivesImageChangesAndFitsWireToken() {
        String scene="00000000-0000-0000-0000-000000000094", a="00000000-0000-0000-0000-000000000001", b="00000000-0000-0000-0000-000000000002";
        String first=LockProtocol.sceneTicket(scene,a), second=LockProtocol.sceneTicket(scene,b);
        assertNotEquals(first,second);assertEquals(scene,LockProtocol.sceneId(first));assertEquals(scene,LockProtocol.sceneId(second));assertTrue(LockProtocol.token(first));assertEquals(79,first.length());
    }
    @Test public void sceneParserRejectsLegacyMalformedAndUriTokens() {
        for(String value:new String[]{null,"","old-ticket","content://private/image","LOCK2:x:y","LOCK3:00000000-0000-0000-0000-000000000094:00000000-0000-0000-0000-000000000001","LOCK2:00000000-0000-0000-0000-00000000009Z:00000000-0000-0000-0000-000000000001"})assertNull(LockProtocol.sceneId(value));
    }
    @Test public void invalidScenePartsCannotBeEncoded() {
        try{LockProtocol.sceneTicket("bad","00000000-0000-0000-0000-000000000001");fail();}catch(IllegalArgumentException expected){ }
    }
    @Test public void inputOnlyMarkersAreExactlyKnownTransientReasons() {
        for(String reason:new String[]{"BUSY_EDIT","UNKNOWN_STATE","STALE_TICKET"})assertTrue(LockProtocol.priorityInputOnly(LockProtocol.inputOnlyPriorityReason(reason)));
        for(String reason:new String[]{null,"NO_ENGINE","UNAUTHORIZED","SOURCE_CHANGED","READY","private text"})assertNull(LockProtocol.inputOnlyPriorityReason(reason));
    }
    @Test public void inputOnlyMarkerDoesNotAcceptPrefixOrNormalReady() {
        for(String reason:new String[]{null,"","READY_NATIVE_INPUT","INPUT_ONLY_NATIVE_PRIORITY_BUSY_EDIT_EXTRA","INPUT_ONLY_NATIVE_PRIORITY"})assertFalse(LockProtocol.priorityInputOnly(reason));
    }
    private LockProtocol.Command command(String id, long seq, long issued, int action) {
        return new LockProtocol.Command("session-1", "ticket-1", id, seq, issued, action);
    }
    @Test public void acceptsOnlyThreeActions() {
        assertTrue(LockProtocol.action(1)); assertTrue(LockProtocol.action(2)); assertTrue(LockProtocol.action(3));
        assertFalse(LockProtocol.action(0)); assertFalse(LockProtocol.action(4)); assertFalse(LockProtocol.action(-1));
    }
    @Test public void tokensAreBoundedAndDoNotCarryUris() {
        assertTrue(LockProtocol.token("0123_a-B:C"));
        for (String bad : new String[]{null, "", "a/b", "a\\b", "content:x/y", "x y", "x\n", "x|y", "中文", "a".repeat(129)})
            assertFalse(LockProtocol.token(bad));
        assertTrue(LockProtocol.token("a".repeat(128)));
    }
    @Test public void normalTtlHasExactInclusiveBoundary() {
        assertTrue(LockProtocol.timely(1, 10, 1010)); assertFalse(LockProtocol.timely(1, 10, 1011));
    }
    @Test public void editHasSeparateAdmissionTtl() {
        assertTrue(LockProtocol.timely(3, 10, 5010)); assertFalse(LockProtocol.timely(3, 10, 5011));
    }
    @Test public void rejectsFutureNegativeAndInvalidActionClocks() {
        assertFalse(LockProtocol.timely(1, 11, 10)); assertFalse(LockProtocol.timely(1, -1, 10));
        assertFalse(LockProtocol.timely(0, 0, 0)); assertFalse(LockProtocol.timely(1, 0, Long.MAX_VALUE));
    }
    private boolean identity(int uid, int expectedUid, String[] packages, String[] signers, boolean enabled, boolean multiple) {
        return LockProtocol.exactIdentity(LockProtocol.MODULE_PACKAGE, LockProtocol.MODULE_CERT,
                uid, expectedUid, packages, signers, enabled, multiple);
    }
    @Test public void acceptsExactSinglePackageAndCurrentFormalSigner() {
        assertTrue(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE}, new String[]{LockProtocol.MODULE_CERT}, true, false));
    }
    @Test public void neverAcceptsSystemSharedUidOrWrongUid() {
        assertFalse(identity(1000, 1000, new String[]{LockProtocol.MODULE_PACKAGE}, new String[]{LockProtocol.MODULE_CERT}, true, false));
        assertFalse(identity(10123, 10124, new String[]{LockProtocol.MODULE_PACKAGE}, new String[]{LockProtocol.MODULE_CERT}, true, false));
    }
    @Test public void sharedUidMemberDoesNotAuthenticateWholeUid() {
        assertFalse(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE, "another.app"}, new String[]{LockProtocol.MODULE_CERT}, true, false));
    }
    @Test public void debugPackageAndWrongCertificateAreRejected() {
        assertFalse(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE + ".debug"}, new String[]{LockProtocol.MODULE_CERT}, true, false));
        assertFalse(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE}, new String[]{LockProtocol.HOST_CERT}, true, false));
    }
    @Test public void historicalExtraOrMissingSignersDoNotPass() {
        assertFalse(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE}, new String[]{LockProtocol.MODULE_CERT, LockProtocol.HOST_CERT}, true, false));
        assertFalse(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE}, null, true, false));
        assertFalse(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE}, new String[]{LockProtocol.MODULE_CERT}, true, true));
    }
    @Test public void disabledAndUnknownPackageQueriesFailClosed() {
        assertFalse(identity(10123, 10123, new String[]{LockProtocol.MODULE_PACKAGE}, new String[]{LockProtocol.MODULE_CERT}, false, false));
        assertFalse(identity(10123, 10123, null, new String[]{LockProtocol.MODULE_CERT}, true, false));
        assertFalse(identity(10123, 10123, new String[0], new String[]{LockProtocol.MODULE_CERT}, true, false));
    }
    @Test public void firstCommandIsAccepted() {
        assertEquals("ACCEPTED", new LockProtocol.Ledger("session-1").admit(command("r1", 1, 100, 1), 101));
    }
    @Test public void duplicateReturnsOriginalBeforeHighWaterAndTtl() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("session-1");
        LockProtocol.Command first = command("r1", 1, 100, 1);
        assertEquals("ACCEPTED", ledger.admit(first, 101));
        ledger.complete("r1", "COMMITTED");
        assertEquals("COMMITTED", ledger.admit(first, 20000));
    }
    @Test public void sameRequestIdWithChangedFieldsConflicts() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("session-1");
        ledger.admit(command("r1", 1, 100, 1), 101);
        assertEquals("REQUEST_ID_CONFLICT", ledger.admit(command("r1", 2, 100, 1), 101));
        assertEquals("REQUEST_ID_CONFLICT", ledger.admit(command("r1", 1, 100, 2), 101));
    }
    @Test public void oldSequenceWithNewIdIsReplay() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("session-1");
        ledger.admit(command("r1", 3, 100, 1), 101);
        assertEquals("REPLAY", ledger.admit(command("r2", 2, 100, 1), 101));
        assertEquals("REPLAY", ledger.admit(command("r3", 3, 100, 1), 101));
    }
    @Test public void staleSessionFailsBeforeAnyDeduplication() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("new-session");
        assertEquals("STALE_SESSION", ledger.admit(command("r1", 1, 100, 1), 101));
    }
    @Test public void expiredRequestDoesNotAdvanceSequence() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("session-1");
        assertEquals("EXPIRED", ledger.admit(command("old", 1, 0, 1), 1001));
        assertEquals("ACCEPTED", ledger.admit(command("new", 1, 1001, 1), 1001));
    }
    @Test public void badRequestAndSequenceOverflowDoNotEnterTable() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("session-1");
        assertEquals("BAD_REQUEST", ledger.admit(null, 1));
        assertEquals("BAD_REQUEST", ledger.admit(command("r1", 0, 1, 1), 1));
        assertEquals("BAD_REQUEST", ledger.admit(command("r2", Long.MAX_VALUE, 1, 1), 1));
        assertEquals(0, ledger.size());
    }
    @Test public void evictionIsBoundedAndEvictedIdCannotReexecute() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("session-1");
        for (int i = 1; i <= 40; i++) assertEquals("ACCEPTED", ledger.admit(command("r" + i, i, 100, 1), 101));
        assertEquals(32, ledger.size()); assertFalse(ledger.contains("r1"));
        assertEquals("REPLAY", ledger.admit(command("r1", 1, 100, 1), 101));
    }
    @Test public void foreignCompletionCannotCreateHistoryOrLeakText() {
        LockProtocol.Ledger ledger = new LockProtocol.Ledger("session-1");
        ledger.complete("foreign", "COMMITTED"); assertEquals(0, ledger.size());
        LockProtocol.Command c = command("r1", 1, 100, 1); ledger.admit(c, 101);
        ledger.complete("r1", "content://private/file"); assertEquals("ACCEPTED", ledger.admit(c, 101));
    }
    @Test(expected = IllegalArgumentException.class) public void invalidLedgerSessionRejected() { new LockProtocol.Ledger("content://bad"); }
}
