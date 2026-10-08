package com.tanhaowen.contextenglish

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.tanhaowen.contextenglish.study.*
import com.tanhaowen.contextenglish.data.WordState
import kotlinx.coroutines.delay
import android.view.HapticFeedbackConstants
import android.os.Build

enum class StudyIcon { BACK, SOUND, HINT, SPELL, STAR, CLOSE }

@Composable
fun StudyGlyph(icon: StudyIcon, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(Modifier.size(24.dp)) {
        val scale=size.width/24f
        fun pt(x: Float,y: Float)=Offset(x*scale,y*scale)
        fun line(x: Float,y: Float,x2: Float,y2: Float) = drawLine(color,pt(x,y),pt(x2,y2),2f*scale,StrokeCap.Round)
        fun path(vararg points: Pair<Float,Float>, close: Boolean = false) {
            val p=Path().apply { moveTo(points[0].first*scale,points[0].second*scale)
                points.drop(1).forEach { lineTo(it.first*scale,it.second*scale) }; if(close) close() }
            drawPath(p,color,style=Stroke(1.8f*scale,cap=StrokeCap.Round))
        }
        when(icon) {
            StudyIcon.BACK -> { line(14f,4f,6f,12f);line(6f,12f,14f,20f) }
            StudyIcon.CLOSE -> { line(5f,5f,19f,19f);line(5f,19f,19f,5f) }
            StudyIcon.SOUND -> {
                path(3f to 9f,7f to 9f,12f to 5f,12f to 19f,7f to 15f,3f to 15f,close=true)
                drawArc(color,-55f,110f,false,pt(9f,7f),androidx.compose.ui.geometry.Size(10f*scale,10f*scale),style=Stroke(1.8f*scale))
                drawArc(color,-55f,110f,false,pt(7f,3f),androidx.compose.ui.geometry.Size(18f*scale,18f*scale),style=Stroke(1.8f*scale))
            }
            StudyIcon.HINT -> {
                drawCircle(color,6f*scale,pt(12f,8f),style=Stroke(1.8f*scale))
                line(8f,13f,9f,18f);line(16f,13f,15f,18f);line(9f,18f,15f,18f);line(10f,21f,14f,21f)
            }
            StudyIcon.SPELL -> {
                path(2f to 5f,22f to 5f,22f to 19f,2f to 19f,close=true)
                for(y in listOf(9f,12f)) for(x in listOf(6f,10f,14f,18f)) line(x,y,x+0.3f,y)
                line(8f,16f,16f,16f)
            }
            StudyIcon.STAR -> path(12f to 2f,15f to 8f,22f to 9f,17f to 14f,18f to 21f,
                12f to 18f,6f to 21f,7f to 14f,2f to 9f,9f to 8f,close=true)
        }
    }
}

@Composable
fun StudyIconButton(icon: StudyIcon, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.semantics { contentDescription=label }) {
        StudyGlyph(icon,if(enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ImmersiveStudyScreen(state: AppUiState, vm: AppViewModel) {
    BackHandler { vm.pauseStudy() }
    val session=state.studySession ?: return
    val app=LocalContext.current.applicationContext as ContextEnglishApp
    val view=LocalView.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground=lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val question=remember(session.id,session.task,state.words) { StudyEngine.question(session,state.words) }
    var lastSound by rememberSaveable(session.id) { mutableStateOf("") }
    DisposableEffect(app) { app.feedback.prepare(); onDispose { app.speech.stop(); app.feedback.stop() } }
    LaunchedEffect(session.task?.key,session.task?.kind,foreground) {
        if(foreground && state.studySettings.autoSpeak && session.feedback==null && question!=null) app.speech.speak(question.word.word)
    }
    LaunchedEffect(session.task?.key,session.feedback) {
        val f=session.feedback ?: return@LaunchedEffect
        if(!foreground || lastSound==session.task?.key) return@LaunchedEffect
        lastSound=session.task?.key.orEmpty()
        app.speech.stop()
        if(state.studySettings.sound) app.feedback.play(if(f.correct) FeedbackTone.CORRECT else FeedbackTone.WRONG)
        if(state.studySettings.haptics) view.performHapticFeedback(if(Build.VERSION.SDK_INT>=30)
            if(f.correct) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
            else if(f.correct) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.LONG_PRESS)
        if(state.studySettings.autoSpeak && question!=null) { delay(200); app.speech.speak(question.word.word) }
    }
    LaunchedEffect(session.task?.key,session.feedback,state.sessionBusy,state.selectedWord,foreground,state.studySettings.autoAdvance) {
        if(foreground && session.feedback?.correct==true && !state.sessionBusy && state.selectedWord==null && state.studySettings.autoAdvance) {
            delay(1100); vm.nextStudy()
        }
    }
    LaunchedEffect(session.finished) { if(session.finished && session.answered>0 && state.studySettings.sound) app.feedback.play(FeedbackTone.COMPLETE) }

    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize().testTag("immersive-study")) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
                StudyIconButton(StudyIcon.BACK,"退出学习并保存进度",vm::pauseStudy)
                val remaining=session.tasks.drop(session.index).map { it.wordId }.distinct()
                Column(Modifier.padding(start=8.dp)) {
                    Text("需新学 ${remaining.count { it in session.newIds }}",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("需复习 ${remaining.count { it !in session.newIds }}",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if(session.combo>=3) Text("连对 ${session.combo}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                Text("${session.index.coerceAtMost(session.tasks.size)} / ${session.tasks.size}",style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(start=12.dp,end=12.dp))
            }
            LinearProgressIndicator(progress={ if(session.tasks.isEmpty()) 1f else session.index.toFloat()/session.tasks.size },
                modifier=Modifier.fillMaxWidth().height(2.dp),trackColor=MaterialTheme.colorScheme.surfaceVariant)
            if(session.finished) {
                StudySummary(session,state,vm)
            } else if(question==null) {
                Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center) {
                    Text("这个词暂时无法组成可靠题目")
                    Text("可以返回词库查看，已有进度不会丢失。",modifier=Modifier.padding(top=12.dp))
                    Button(onClick=vm::pauseStudy,modifier=Modifier.padding(top=20.dp)) { Text("返回") }
                }
            } else {
                AnimatedContent(targetState=question,transitionSpec={ fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                    modifier=Modifier.weight(1f),label="studyQuestion") { q ->
                    QuestionBody(q,session,state,vm)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    StudyIconButton(StudyIcon.SPELL,if(question.task.kind==TaskKind.SPELLING) "切换选择题" else "拼写练习",vm::toggleSpelling,
                        enabled=session.feedback==null && !state.sessionBusy && question.task.kind!=TaskKind.CONTEXT)
                    StudyIconButton(StudyIcon.HINT,"显示提示",vm::hintStudy,enabled=session.feedback==null && !state.sessionBusy)
                    StudyIconButton(StudyIcon.SOUND,"朗读当前单词",{ app.speech.speak(question.word.word) })
                }
            }
        }
    }
}

@Composable
private fun QuestionBody(q: StudyQuestion, session: StudySession, state: AppUiState, vm: AppViewModel) {
    val app=LocalContext.current.applicationContext as ContextEnglishApp
    var typed by rememberSaveable(q.task.key,q.task.kind) { mutableStateOf("") }
    val current=session.task?.key==q.task.key
    val feedback=if(current) session.feedback else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=22.dp),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Spacer(Modifier.height(54.dp))
        Column(Modifier.fillMaxWidth().heightIn(min=160.dp).padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center) {
            if(q.task.kind==TaskKind.CONTEXT) {
                Text("语境练习",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q.task.prompt,style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center,modifier=Modifier.padding(top=16.dp))
            } else if(q.task.kind==TaskKind.SPELLING) {
                Text(StudyEngine.meaning(q.word),style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
                Text("听音或根据词义拼写",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.padding(top=12.dp))
            } else {
                Text(q.word.word,fontSize=if(q.word.word.length>15) 34.sp else 42.sp,fontWeight=FontWeight.SemiBold,
                    color=MaterialTheme.colorScheme.primary,textAlign=TextAlign.Center,
                    modifier=Modifier.clickable { app.speech.speak(q.word.word) }.testTag("study-word"))
                if(state.studySettings.showPhonetic && q.word.phonetic.isNotBlank()) Text(q.word.phonetic,
                    fontSize=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=12.dp))
            }
        }
        Spacer(Modifier.weight(1f,fill=false).heightIn(min=28.dp,max=140.dp))
        if(session.hinted && feedback==null) {
            Text(if(q.task.kind==TaskKind.SPELLING) "首字母：${q.word.word.first()} · ${q.word.word.length} 个字符"
                else StudyEngine.meaning(q.word),color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.padding(bottom=14.dp),textAlign=TextAlign.Center)
        }
        if(q.task.kind==TaskKind.SPELLING) {
            OutlinedTextField(value=typed,onValueChange={ typed=it.take(100) },enabled=feedback==null && !state.sessionBusy,
                singleLine=true,label={ Text("输入英文单词") },modifier=Modifier.fillMaxWidth().testTag("spelling-input"),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii,imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={ if(typed.isNotBlank()) vm.answerStudy(typed=typed) }))
            if(feedback==null) Button(onClick={ vm.answerStudy(typed=typed) },enabled=typed.isNotBlank() && !state.sessionBusy,
                modifier=Modifier.fillMaxWidth().padding(top=12.dp)) { Text("检查拼写") }
        } else {
            q.options.forEachIndexed { i,option ->
                val correct=feedback!=null && i==q.answer
                val wrong=feedback!=null && !feedback.correct && i==feedback.selected
                val background by animateColorAsState(when { correct -> MaterialTheme.colorScheme.primaryContainer
                    wrong -> MaterialTheme.colorScheme.errorContainer else -> MaterialTheme.colorScheme.surface },tween(100),label="answerColor")
                Surface(onClick={ vm.answerStudy(i) },enabled=feedback==null && !state.sessionBusy && current,
                    shape=RoundedCornerShape(15.dp),color=background,tonalElevation=if(feedback==null) 1.dp else 0.dp,
                    modifier=Modifier.fillMaxWidth().padding(bottom=10.dp).testTag("answer-$i")) {
                    Row(Modifier.padding(horizontal=20.dp,vertical=20.dp).heightIn(min=28.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(option,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f),lineHeight=25.sp,
                            color=when { correct -> MaterialTheme.colorScheme.onPrimaryContainer; wrong -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurface })
                        if(correct || wrong) Text(if(correct) "✓" else "×",fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=8.dp))
                    }
                }
            }
        }
        if(feedback==null) {
            TextButton(onClick={ vm.answerStudy(unknown=true) },enabled=!state.sessionBusy,modifier=Modifier.testTag("dont-know")) { Text("不认识，先学一下") }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                Text(if(feedback.correct) if(feedback.credited) "正确" else "提示后答对 · 稍后再复习" else "记住这个词，稍后再试",
                    color=if(feedback.correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("answer-feedback"))
                if(!feedback.correct) Text("${q.word.word} · ${StudyEngine.meaning(q.word)}",modifier=Modifier.padding(top=6.dp))
                if(!feedback.correct && q.word.example.isNotBlank()) {
                    Text(q.word.example,modifier=Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodyMedium)
                    Text(q.word.exampleTranslation,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.coachNotes[q.word.id]?.let { if(!feedback.correct) Text(it.tip,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp)) }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={ vm.selectWord(q.word) },modifier=Modifier.testTag("study-detail")) { Text("例句与讲解") }
                    if(!feedback.correct || !state.studySettings.autoAdvance) Button(onClick=vm::nextStudy,enabled=!state.sessionBusy,
                        modifier=Modifier.testTag("continue-study")) { Text(if(feedback.correct) "继续" else "记住了，继续") }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.StudySummary(s: StudySession,state: AppUiState,vm: AppViewModel) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Spacer(Modifier.height(54.dp))
        Text(if(s.answered==0) "今日任务已完成" else "这一组，完成了",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
        Text("学习 ${s.originalIds.size} 词 · 作答 ${s.answered} 次",modifier=Modifier.padding(top=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${s.accuracy}%",fontSize=54.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=28.dp))
        Text("无提示正确率",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("最高连对 ${s.bestCombo} · 连续学习 ${state.streak} 天",modifier=Modifier.padding(top=24.dp))
        if(s.wrongIds.isNotEmpty()) {
            Text("这些词值得再看一眼",modifier=Modifier.padding(top=28.dp),fontWeight=FontWeight.Medium)
            state.words.filter { it.id in s.wrongIds }.forEach { w ->
                Row(Modifier.fillMaxWidth().clickable { vm.selectWord(w) }.padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(w.word,modifier=Modifier.weight(1f));Text("查看",color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("一次答对不等于长期掌握，后续会按间隔继续复习。",style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=24.dp),textAlign=TextAlign.Center)
        Button(onClick=vm::pauseStudy,modifier=Modifier.fillMaxWidth()) { Text("完成，返回今日") }
    }
}
