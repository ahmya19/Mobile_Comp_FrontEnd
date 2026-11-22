package com.example.pinpoint

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pinpoint.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host-seeded, opt-in checks. Only ordinary authenticated reads run in the emulator. */
@RunWith(AndroidJUnit4::class)
class HostedReportFeedTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val args=InstrumentationRegistry.getArguments()
    private var backend:SupabaseBackend?=null
    private val stage=File(context.filesDir,"cp21-report-stage.json")
    private val oracleFile=File(context.filesDir,"cp21-report-oracle.json")
    @After fun signOut() { runBlocking { backend?.takeIf { it.auth.currentUser!=null }?.auth?.signOut() } }

    @Test fun hostedFiltersPagesAndInsertBurstMatchIndependentOrdering()=runBlocking {
        assumeTrue("Hosted report checks require explicit opt-in",args.getString("allowHostedReportChecks")=="true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Use an isolated emulator" }
        check(BuildConfig.SUPABASE_URL=="https://jaysxjjdvaitgrbqjlhe.supabase.co")
        val prefix=requireNotNull(args.getString("fixture_prefix"))
        require(prefix.matches(Regex("CP21-[0-9]{8}-[A-Z][0-9]{2}")))
        val group=UUID.fromString(requireNotNull(args.getString("fixture_group"))).toString()
        stage.delete();oracleFile.delete()
        val configured=requireNotNull(SupabaseBackend.create(context)).also { backend=it }
        configured.auth.signIn(requireNotNull(args.getString("citizen_email")),requireNotNull(args.getString("citizen_password")))
        assertEquals(args.getString("citizen_uid"),configured.auth.currentUser?.id)
        val repository=PinPointRepository(configured)
        val alpha=ReportFilter(barangay="$prefix-Alpha")
        val feed=ReportFeed(repository,false)
        feed.refresh(alpha);assertNull(feed.state.value.error);assertEquals(25,feed.state.value.reports.size)
        feed.loadMore();assertNull(feed.state.value.error);assertEquals(50,feed.state.value.reports.size)
        val before=feed.state.value.reports.map { it.id }
        stage.writeText(JSONObject().put("phase","ready-for-burst").put("prefix",prefix)
            .put("group",group).put("loaded_ids",JSONArray(before)).toString())
        withTimeout(180_000) { while(!oracleFile.isFile) delay(250) }
        val oracle=JSONObject(oracleFile.readText())
        assertEquals(prefix,oracle.getString("prefix"));assertEquals(group,oracle.getString("group"))
        fun ids(array:JSONArray)=(0 until array.length()).map { array.getString(it) }
        assertEquals(ids(oracle.getJSONArray("before_first_50")),before)
        val expectedAlpha=ids(oracle.getJSONArray("alpha_ids"))
        feed.refresh();assertNull(feed.state.value.error)
        assertEquals("Refresh must preserve a contiguous loaded window",expectedAlpha.take(50),feed.state.value.reports.map { it.id })
        var pages=0
        while(feed.state.value.next!=null) {
            check(++pages<8);feed.loadMore();assertNull(feed.state.value.error)
        }
        assertEquals(expectedAlpha,feed.state.value.reports.map { it.id })
        assertEquals(expectedAlpha.size,feed.state.value.reports.map { it.id }.distinct().size)
        assertEquals(ids(oracle.getJSONArray("alpha_coordinate_ids")).toSet(),
            feed.state.value.reports.filter { it.latitude!=null && it.longitude!=null }.map { it.id }.toSet())

        val cases=oracle.getJSONArray("cases")
        repeat(cases.length()) { i ->
            val case=cases.getJSONObject(i);val f=case.getJSONObject("filter")
            fun value(key:String)=f.opt(key)?.takeUnless { it==JSONObject.NULL }?.toString()
            val filter=ReportFilter(mine=f.optBoolean("mine",false),barangay=value("barangay"),
                category=value("category"),status=value("status"),after=value("after"),before=value("before"))
            val expected=ids(case.getJSONArray("ids"))
            val checked=ReportFeed(repository,filter.mine)
            checked.refresh(filter);assertNull(case.getString("name"),checked.state.value.error)
            assertEquals("Filter reset must start at one page: ${case.getString("name")}",expected.take(25),checked.state.value.reports.map { it.id })
            var extra=0
            while(checked.state.value.next!=null) {
                check(++extra<8);checked.loadMore();assertNull(checked.state.value.error)
            }
            assertEquals(case.getString("name"),expected,checked.state.value.reports.map { it.id })
            assertEquals(expected.size,checked.state.value.reports.map { it.id }.distinct().size)
        }
        val beta=ReportFilter(barangay="$prefix-Beta")
        val betaCase=(0 until cases.length()).map { cases.getJSONObject(it) }.single { it.getString("name")=="community-beta" }
        val betaIds=ids(betaCase.getJSONArray("ids"))
        feed.refresh(beta);assertNull(feed.state.value.error)
        assertEquals(betaIds.take(25),feed.state.value.reports.map { it.id })
        val first=async(start=CoroutineStart.UNDISPATCHED) { feed.refresh(alpha) }
        val latest=async(start=CoroutineStart.UNDISPATCHED) { feed.refresh(beta) }
        first.await();latest.await()
        assertEquals(beta,feed.state.value.filter)
        assertEquals("An earlier response must not overwrite the latest filter",betaIds.take(25),feed.state.value.reports.map { it.id })
        stage.writeText(JSONObject().put("phase","completed").put("prefix",prefix)
            .put("filters_match",true).put("no_gaps_or_duplicates",true)
            .put("filter_cases",cases.length()).put("alpha_count",expectedAlpha.size)
            .put("rapid_filter_final_state_matches",true).put("coordinate_ids_match",true).toString())
    }
}
