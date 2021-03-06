// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.learn.lesson.kimpl

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import com.intellij.util.DocumentUtil
import training.commands.kotlin.PreviousTaskInfo
import training.commands.kotlin.TaskContext
import training.commands.kotlin.TaskTestContext
import training.learn.ActionsRecorder
import training.learn.lesson.LessonManager
import training.ui.LearnToolWindowFactory
import training.ui.LearningUiManager
import java.awt.Component

class LessonExecutor(val lesson: KLesson, val project: Project) : Disposable {
  private data class TaskInfo(val content: () -> Unit,
                              var restoreIndex: Int,
                              val shownTaskIndex: Int?,
                              var messagesNumberBeforeStart: Int = 0,
                              var rehighlightComponent: (() -> Component)? = null,
                              var userVisibleInfo: PreviousTaskInfo? = null)

  val editor: Editor
    get() = FileEditorManager.getInstance(project).selectedTextEditor ?: error("no editor selected now")

  data class TaskCallbackData(var shouldRestoreToTask: (() -> TaskContext.TaskId?)? = null,
                              var delayMillis: Int = 0)

  // Just tasks with messages to the panel. Do not count technical intermediate tasks.
  private var currentProgressTaskNumber = 0

  private var isUnderTaskProcessing = false
  private val taskActions: MutableList<TaskInfo> = ArrayList()

  var foundComponent: Component? = null
  var rehighlightComponent: (() -> Component)? = null

  private var currentRecorder: ActionsRecorder? = null
  private var currentRestoreRecorder: ActionsRecorder? = null
  private var currentTaskIndex = 0

  private val parentDisposable: Disposable = LearnToolWindowFactory.learnWindowPerProject[project]?.parentDisposable ?: project

  @Volatile
  var hasBeenStopped = false
    private set

  init {
    Disposer.register(parentDisposable, this)
  }

  private fun addTaskAction(shownTaskIndex: Int? = null, content: () -> Unit) {
    taskActions.add(TaskInfo(content, taskActions.size - 1, shownTaskIndex))
  }

  fun getUserVisibleInfo(index: Int): PreviousTaskInfo {
    return taskActions[index].userVisibleInfo ?: throw IllegalArgumentException("No information available for task $index")
  }

  fun waitBeforeContinue(delayMillis: Int) {
    if (isUnderTaskProcessing) {
      throw IllegalStateException("Delay should be specified between tasks!")
    }

    addTaskAction {
      Alarm().addRequest({ processNextTask(currentTaskIndex + 1) }, delayMillis)
    }
  }

  fun task(taskContent: TaskContext.() -> Unit) {
    assert(ApplicationManager.getApplication().isDispatchThread)
    if (isUnderTaskProcessing) {
      throw IllegalStateException("Nested tasks are not permitted!")
    }

    val isRealTask = LessonExecutorUtil.isRealTask(taskContent)
    val shownTaskNumber = if (isRealTask) currentProgressTaskNumber++ else null
    addTaskAction(shownTaskNumber) { processTask(taskContent) }
  }

  override fun dispose() {
    if (!hasBeenStopped) {
      assert(ApplicationManager.getApplication().isDispatchThread)
      disposeRecorders()
      hasBeenStopped = true
      taskActions.clear()
      Disposer.dispose(this)
    }
  }

  fun stopLesson() {
    dispose()
  }

  private fun disposeRecorders() {
    currentRecorder?.let { Disposer.dispose(it) }
    currentRecorder = null
    currentRestoreRecorder?.let { Disposer.dispose(it) }
    currentRestoreRecorder = null
  }

  fun prepareSample(sample: LessonSample) {
    addSimpleTaskAction {
      setSample(sample)
    }
  }

  fun caret(position: LessonSamplePosition) {
    addSimpleTaskAction {
      setCaret(position)
    }
  }

  fun caret(offset: Int) {
    addSimpleTaskAction { OpenFileDescriptor(project, virtualFile, offset).navigateIn(editor) }
  }

  fun caret(line: Int, column: Int) {
    addSimpleTaskAction { OpenFileDescriptor(project, virtualFile, line - 1, column - 1).navigateIn(editor) }
  }

  fun caret(text: String, select: Boolean) {
    addSimpleTaskAction l@{
      val start = getStartOffsetForText(text, editor, project) ?: return@l
      editor.caretModel.moveToOffset(start)
      if (select) {
        editor.selectionModel.setSelection(start, start + text.length)
      }
    }
  }

  val virtualFile: VirtualFile
    get() = FileDocumentManager.getInstance().getFile(editor.document) ?: error("No Virtual File")

  fun processNextTask(taskIndex: Int) {
    isUnderTaskProcessing = true
    // ModalityState.current() or without argument - cannot be used: dialog steps can stop to work.
    // Good example: track of rename refactoring
    invokeLater(ModalityState.any()) {
      disposeRecorders()
      currentTaskIndex = taskIndex
      processNextTask2()
    }
  }

  private fun processNextTask2() {
    LessonManager.instance.clearRestoreMessage()
    assert(ApplicationManager.getApplication().isDispatchThread)
    if (currentTaskIndex == taskActions.size) {
      LessonManager.instance.passLesson(project, lesson)
      disposeRecorders()
      return
    }
    val taskInfo = taskActions[currentTaskIndex]
    taskInfo.shownTaskIndex?.let {
      LearningUiManager.activeToolWindow?.learnPanel?.updateLessonProgress(currentProgressTaskNumber, it)
    }
    taskInfo.messagesNumberBeforeStart = LessonManager.instance.messagesNumber()
    setUserVisibleInfo()
    taskInfo.content()
  }

  private fun setUserVisibleInfo() {
    val taskInfo = taskActions[currentTaskIndex]
    // do not reset information from the previous tasks if it is available already
    if (taskInfo.userVisibleInfo == null) {
      taskInfo.userVisibleInfo = object : PreviousTaskInfo {
        override val text: String = editor.document.text
        override val position: LogicalPosition = editor.caretModel.currentCaret.logicalPosition
        override val sample: LessonSample = prepareSampleFromCurrentState(editor)
        override val ui: Component? = foundComponent
      }
      taskInfo.rehighlightComponent = rehighlightComponent
    }
    //Clear user visible information for later tasks
    for (i in currentTaskIndex + 1 until taskActions.size) {
      taskActions[i].userVisibleInfo = null
      taskActions[i].rehighlightComponent = null
    }
    foundComponent = null
    rehighlightComponent = null
  }

  private fun processTask(taskContent: TaskContext.() -> Unit) {
    assert(ApplicationManager.getApplication().isDispatchThread)
    val recorder = ActionsRecorder(project, editor.document, this)
    currentRecorder = recorder
    val taskCallbackData = TaskCallbackData()
    val taskContext = TaskContextImpl(this, recorder, currentTaskIndex, taskCallbackData)
    taskContext.apply(taskContent)

    if (taskContext.steps.isEmpty()) {
      processNextTask(currentTaskIndex + 1)
      return
    }

    chainNextTask(taskContext, recorder, taskCallbackData)

    processTestActions(taskContext)
  }

  internal fun applyRestore(taskContext: TaskContextImpl, restoreId: TaskContext.TaskId? = null) {
    taskContext.steps.forEach { it.cancel(true) }
    val restoreIndex = restoreId?.idx ?: taskActions[taskContext.taskIndex].restoreIndex
    val restoreInfo = taskActions[restoreIndex]
    restoreInfo.rehighlightComponent?.let { it() }
    LessonManager.instance.resetMessagesNumber(restoreInfo.messagesNumberBeforeStart)
    processNextTask(restoreIndex)
  }

  /** @return a callback to clear resources used to track restore */
  private fun checkForRestore(taskContext: TaskContextImpl,
                              taskCallbackData: TaskCallbackData): () -> Unit {
    lateinit var clearRestore: () -> Unit
    fun restoreTask(restoreId: TaskContext.TaskId) {
      applyRestore(taskContext, restoreId)
    }
    fun restore(restoreId: TaskContext.TaskId) {
      clearRestore()
      invokeLater(ModalityState.any()) { // restore check must be done after pass conditions (and they will be done during current event processing)
        if (!isTaskCompleted(taskContext)) {
          restoreTask(restoreId)
        }
      }
    }

    val shouldRestoreToTask = taskCallbackData.shouldRestoreToTask ?: return {}

    fun checkFunction(): Boolean {
      if (hasBeenStopped) {
        // Strange situation
        clearRestore()
        return false
      }
      val restoreId = shouldRestoreToTask()
      return if (restoreId != null) {
        if (taskCallbackData.delayMillis == 0) restore(restoreId)
        else Alarm().addRequest({ restore(restoreId) }, taskCallbackData.delayMillis)
        true
      }
      else false
    }

    // Not sure about use-case when we need to check restore at the start of current task
    // But it theoretically can be needed in case of several restores of dependent steps
    if (checkFunction()) return {}

    val restoreRecorder = ActionsRecorder(project, editor.document, this)
    currentRestoreRecorder = restoreRecorder
    val restoreFuture = restoreRecorder.futureCheck { checkFunction() }
    clearRestore = {
      if (!restoreFuture.isDone) {
        restoreFuture.cancel(true)
      }
    }
    return clearRestore
  }

  private fun chainNextTask(taskContext: TaskContextImpl,
                            recorder: ActionsRecorder,
                            taskCallbackData: TaskCallbackData) {
    val clearRestore = checkForRestore(taskContext, taskCallbackData)

    recorder.tryToCheckCallback()

    taskContext.steps.forEach { step ->
      step.thenAccept {
        assert(ApplicationManager.getApplication().isDispatchThread)
        val taskHasBeenDone = isTaskCompleted(taskContext)
        if (taskHasBeenDone) {
          clearRestore()
          LessonManager.instance.passExercise()
          if(foundComponent == null) foundComponent = taskActions[currentTaskIndex].userVisibleInfo?.ui
          if(rehighlightComponent == null) rehighlightComponent = taskActions[currentTaskIndex].rehighlightComponent
          processNextTask(currentTaskIndex + 1)
        }
      }
    }
  }

  private fun isTaskCompleted(taskContext: TaskContextImpl) = taskContext.steps.all { it.isDone && it.get() }

  private fun addSimpleTaskAction(taskAction: () -> Unit) {
    assert(ApplicationManager.getApplication().isDispatchThread)
    if (!isUnderTaskProcessing) {
      addTaskAction {
        taskAction()
        processNextTask(currentTaskIndex + 1)
      }
    }
    else {
      // allow some simple tasks like caret move and so on...
      taskAction()
    }
  }

  private fun processTestActions(taskContext: TaskContextImpl) {
    if (TaskTestContext.inTestMode) {
      LessonManager.instance.testActionsExecutor.execute {
        taskContext.testActions.forEach { it.run() }
      }
    }
  }

  fun setSample(sample: LessonSample) {
    invokeLater(ModalityState.NON_MODAL) {
      (editor as? EditorEx)?.isViewer = false
      setDocumentCode(sample.text)
      setCaret(sample.getPosition(0))
    }
  }

  private fun setCaret(position: LessonSamplePosition) {
    position.selection?.let { editor.selectionModel.setSelection(it.first, it.second) }
    editor.caretModel.moveToOffset(position.startOffset)
  }

  private fun setDocumentCode(code: String) {
    val document = editor.document
    DocumentUtil.writeInRunUndoTransparentAction {
      val documentReference = DocumentReferenceManager.getInstance().create(document)
      UndoManager.getInstance(project).nonundoableActionPerformed(documentReference, false)
      document.replaceString(0, document.textLength, code)
    }
    PsiDocumentManager.getInstance(project).commitDocument(document)
    doUndoableAction(project)
    updateGutter(editor)
  }

  private fun getStartOffsetForText(text: String, editor: Editor, project: Project): Int? {
    val document = editor.document

    val indexOf = document.charsSequence.indexOf(text)
    if (indexOf != -1) {
      return indexOf
    }
    return null
  }

  private fun doUndoableAction(project: Project) {
    CommandProcessor.getInstance().executeCommand(project, {
      UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction() {
        override fun undo() {}
        override fun redo() {}
      })
    }, null, null)
  }

  private fun updateGutter(editor: Editor) {
    val editorGutterComponentEx = editor.gutter as EditorGutterComponentEx
    editorGutterComponentEx.revalidateMarkup()
  }
}