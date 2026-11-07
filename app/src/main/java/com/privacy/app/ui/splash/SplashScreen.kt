package com.privacy.app.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.AppInfo
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.face.FaceMood
import com.privacy.app.ui.face.Gesture
import com.privacy.app.ui.face.PixelAvatar
import com.privacy.app.ui.theme.PrivacyTheme
import com.privacy.app.ui.theme.Mono
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Opening screen: the pixel assistant greets you for a few seconds (words typed out, matching gesture),
 * with the P.R.I.V.A.C.Y. name and its full form at the bottom. First launch asks for your name and gender instead.
 */
@Composable
fun SplashScreen(vm: MainViewModel, onDone: () -> Unit) {
    val s = vm.g.settings
    val fontKey by s.fontKey.collectAsStateWithLifecycle()
    val call by s.userCall.collectAsStateWithLifecycle()
    val name by s.userName.collectAsStateWithLifecycle()
    val aName by s.assistantName.collectAsStateWithLifecycle()
    val gender by s.userGender.collectAsStateWithLifecycle()
    var greeting by remember { mutableStateOf<Greeting?>(null) }
    val firstRun = remember { !s.profileDone }
    var editing by remember { mutableStateOf(firstRun) }
    var typed by remember { mutableStateOf(0) }
    var settingUp by remember { mutableStateOf(false) }
    val setupFill by androidx.compose.animation.core.animateFloatAsState(if (settingUp) 1f else 0f, androidx.compose.animation.core.tween(if (com.privacy.app.ui.Anim.enabled) 3200 else 10, easing = androidx.compose.animation.core.LinearEasing), label = "setup")

    LaunchedEffect(Unit) {
        val tasks = vm.g.db.taskDao().activeNow()
        val today = LocalDate.now().toEpochDay()
        val open = tasks.filter { it.status != com.privacy.app.data.TaskStatus.COMPLETED }
        val startOfToday = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val done = tasks.count { it.status == com.privacy.app.data.TaskStatus.COMPLETED && (it.completedAt ?: 0L) >= startOfToday }
        val last = s.lastActiveAt
        val gp = vm.g.context.getSharedPreferences("greet", 0)
        val recent = (gp.getString("recent", "") ?: "").split("|").filter { it.isNotBlank() }
        greeting = Greetings.pick(
            GreetingContext(
                call = call, name = name, now = LocalDateTime.now(), openTasks = open.size, doneToday = done,
                overdue = open.count { it.dueDay != null && it.dueDay < today },
                hoursAway = if (last <= 0L) -1 else (System.currentTimeMillis() - last) / 3_600_000L,
                firstRun = firstRun, assistantName = aName,
                memory = runCatching { vm.g.db.memoryDao().all().randomOrNull()?.content }.getOrNull(),
                noteTitle = runCatching { vm.g.db.noteDao().recent(5).randomOrNull()?.title }.getOrNull()
            ), recent
        ).also { gp.edit().putString("recent", (recent + it.line).takeLast(10).joinToString("|")).apply() }
    }
    val g = greeting
    LaunchedEffect(g) {
        if (g == null) return@LaunchedEffect
        typed = 0
        while (typed < g.line.length) { delay(16); typed++ }
        if (!editing) { delay(if (settingUp) 2200 else 1100); onDone() }
    }

    val themeMode by s.themeMode.collectAsStateWithLifecycle()
    PrivacyTheme(darkTheme = com.privacy.app.ui.theme.isDarkFor(themeMode), fontKey = fontKey) {
        val ink = MaterialTheme.colorScheme.onBackground
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!editing) onDone() }
        ) {
            val scroll = androidx.compose.foundation.rememberScrollState()
            val imeBottom = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
            // the keyboard must never cover the name field: the page scrolls to the bottom whenever the keyboard opens
            LaunchedEffect(imeBottom, editing) { if (editing) { delay(80); scroll.animateScrollTo(scroll.maxValue) } }
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(if (editing) 12.dp else 36.dp))
                PixelAvatar(
                    size = if (editing) 150.dp else 270.dp, color = ink,
                    gesture = g?.gesture ?: Gesture.WAVE,
                    state = if (g != null && typed < g.line.length) com.privacy.app.ui.face.AvatarState.TALKING else com.privacy.app.ui.face.AvatarState.IDLE,
                    autoGestures = false
                )
                Spacer(Modifier.height(18.dp))
                if (g != null) {
                    Text(g.line.take(typed), color = ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 30.sp)
                    AnimatedVisibility(visible = typed >= g.line.length, enter = fadeIn()) {
                        Text(g.sub, color = ink.copy(alpha = 0.65f), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp), lineHeight = 21.sp)
                    }
                }
                if (settingUp) {
                    Spacer(Modifier.height(18.dp))
                    com.privacy.app.ui.tasks.PixelProgressBar(setupFill, cols = 22, rows = 3, strength = 0.9f, modifier = Modifier.padding(horizontal = 12.dp))
                    Text("setting things up…", color = ink.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (editing) {
                    Spacer(Modifier.height(16.dp))
                    var nm by remember { mutableStateOf(name) }
                    var gd by remember { mutableStateOf(gender) }
                    val ctx0 = androidx.compose.ui.platform.LocalContext.current
                    val scope0 = androidx.compose.runtime.rememberCoroutineScope()
                    val gEmail by com.privacy.app.credits.Credits.email.collectAsStateWithLifecycle()
                    val gName by com.privacy.app.credits.Credits.name.collectAsStateWithLifecycle()
                    var googleErr by remember { mutableStateOf<String?>(null) }
                    var googleTried by remember { mutableStateOf(false) }
                    fun proceed(nameIn: String, genderIn: String) {
                        s.setUserName(nameIn); s.setUserGender(genderIn)
                        val first = nameIn.trim().split(" ").firstOrNull().orEmpty()
                        greeting = Greeting(
                            if (first.isBlank()) "Nice to meet you!" else "Nice name, $first!",
                            "Let me set the app up for you…", Gesture.CHEER, com.privacy.app.ui.face.FaceMood.HAPPY
                        )
                        typed = 0; settingUp = true; editing = false
                    }
                    // signed in with Google (here or after the browser fallback): take the name, decide Sir / Ma'am from it, and go on
                    LaunchedEffect(gEmail, gName) {
                        if (googleTried && gEmail.isNotBlank()) {
                            val full = gName.ifBlank { gEmail.substringBefore('@') }.take(30)
                            val first = full.trim().split(" ").firstOrNull().orEmpty()
                            proceed(full, if (com.privacy.app.voice.GroqSpeech.nameLooksFemale(first)) "female" else "male")
                        }
                    }
                    com.privacy.app.ui.GoogleButton(onClick = {
                        googleTried = true; googleErr = null
                        val act = generateSequence(ctx0) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
                        if (act != null) scope0.launch { googleErr = com.privacy.app.credits.Credits.signInWithGoogle(act) }
                        else com.privacy.app.credits.Credits.startSignIn(ctx0)
                    })
                    googleErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Text("We just collect your NAME and EMAIL – only for buying credits. No spam, ever. We are PRIVACY and we stand with it.", color = ink.copy(alpha = 0.55f), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                    Text("or enter it yourself", color = ink.copy(alpha = 0.55f), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(nm, { nm = it.take(30) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("male" to "Sir", "female" to "Ma'am").forEach { (v, l) ->
                            FilterChip(selected = gd == v, onClick = { gd = v }, label = { Text("Call me $l") })
                        }
                    }
                    Button(
                        onClick = {
                            s.setUserName(nm); s.setUserGender(gd)
                            val first = nm.trim().split(" ").firstOrNull().orEmpty()
                            greeting = Greeting(
                                if (first.isBlank()) "Nice to meet you!" else "Nice name, $first!",
                                "Let me set the app up for you…", Gesture.CHEER, com.privacy.app.ui.face.FaceMood.HAPPY
                            )
                            typed = 0; settingUp = true; editing = false
                        },
                        enabled = gd.isNotBlank(), modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
                    ) { Text("Continue") }
                }
              }
                Spacer(Modifier.height(12.dp))
                Text(AppInfo.NAME, color = ink, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 5.sp)
                Text(
                    AppInfo.FULL_FORM, color = ink.copy(alpha = 0.5f), fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
                )
            }
        }
    }
}
