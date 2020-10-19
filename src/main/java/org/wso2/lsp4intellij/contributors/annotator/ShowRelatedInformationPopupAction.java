package org.wso2.lsp4intellij.contributors.annotator;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Iconable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBList;
import com.intellij.util.IncorrectOperationException;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.utils.FileUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

class ShowRelatedInformationPopupAction implements IntentionAction, Iconable {

  private final Diagnostic diagnostic;
  private RangeHighlighter highlighter = null;

  public ShowRelatedInformationPopupAction(Diagnostic diagnostic) {
    this.diagnostic = diagnostic;
  }

  @Override
  public @NotNull @IntentionName String getText() {
    return "Find cause";
  }

  @Override
  public @NotNull @IntentionFamilyName String getFamilyName() {
    return "Lsp Find cause";
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    return true;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {

    List<AnAction> actions = new ArrayList<>();
    final String uri = FileUtils.VFSToURI(file.getVirtualFile());
    if (uri == null) {
      return;
    }
    actions.add(new InformationItem(uri, diagnostic));
    actions.add(Separator.create());

    for (DiagnosticRelatedInformation relatedInformation : diagnostic.getRelatedInformation()) {
      actions.add(new InformationItem(relatedInformation));
    }

    String title = "Related Information";
    DataContext context = DataManager.getInstance().getDataContext(editor.getComponent());
    DefaultActionGroup group = new DefaultActionGroup(actions);

    ListPopup popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(title, group, context, JBPopupFactory.ActionSelectionAid.MNEMONICS, true);

    popup.addListener(new JBPopupListener() {
      @Override
      public void onClosed(@NotNull LightweightWindowEvent event) {
        if (highlighter != null) {
          editor.getMarkupModel().removeHighlighter(highlighter);
          highlighter = null;
        }
      }
    });

    popup.addListSelectionListener(event -> {
      // preview selection
      @SuppressWarnings("rawtypes")
      JBList list = (JBList) event.getSource();

      final VirtualFile vf;
      final Range range;
      if (list.getSelectedIndex() == 0) {
        range = diagnostic.getRange();
        vf = file.getVirtualFile();
      } else {
        final DiagnosticRelatedInformation diagnosticRelatedInformation = diagnostic.getRelatedInformation().get(list.getSelectedIndex() - 1);
        range = diagnosticRelatedInformation.getLocation().getRange();
        vf = FileUtils.virtualFileFromURI(diagnosticRelatedInformation.getLocation().getUri());
      }

      if (vf == null) {
        return;
      }
      // focus editor respective to selected related informations uri
      Editor previewEditor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true);
      if (previewEditor == null) {
        return;
      }

      final ScrollingModel scrollingModel = previewEditor.getScrollingModel();
      scrollingModel.scrollHorizontally(range.getStart().getLine());
      scrollingModel.scrollTo(new LogicalPosition(range.getStart().getLine(), range.getStart().getCharacter()), ScrollType.CENTER_UP);

      // show hint on that location
      MarkupModel model = editor.getMarkupModel();
      int from = editor.logicalPositionToOffset(new LogicalPosition(range.getStart().getLine(), range.getStart().getCharacter()));
      int to = editor.logicalPositionToOffset(new LogicalPosition(range.getEnd().getLine(), range.getEnd().getCharacter()));
      // detect: until end of line -> dont wrap to "before first character"
      to = range.getEnd().getCharacter() == 0 ? to - 1 : to;

      if (highlighter != null) {
        model.removeHighlighter(highlighter);
        highlighter.dispose();
      }

      // TODO: change to TextAttributesKey to allow for custom higlightcolor
      highlighter = model.addRangeHighlighter(from, to, HighlighterLayer.SELECTION, new TextAttributes(JBColor.BLACK, JBColor.LIGHT_GRAY, null, EffectType.BOXED, 0), HighlighterTargetArea.EXACT_RANGE);

    });

    final Point aPointOnComponent = editor.visualPositionToXY(editor.getCaretModel().getVisualPosition());
    aPointOnComponent.y += (editor.getLineHeight() + 2);
    // TODO: handle overlapping / hiding the highlight with popup
    popup.show(new RelativePoint(editor.getComponent(), aPointOnComponent));

  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }

  @Override
  public Icon getIcon(int flags) {
    return AllIcons.Actions.IntentionBulb;
  }


  private static class InformationItem extends AnAction {
    @NotNull Location loc;

    /**
     * to display the origin item
     */
    InformationItem(@NotNull String uri, @NotNull Diagnostic diag) {
      super("Origin", "Links to origin: " + FileUtils.shortenFileUri(uri) + " " + FileUtils.positionToString(diag.getRange().getStart()), null);
      loc = new Location(uri, diag.getRange());
    }

    InformationItem(@NotNull DiagnosticRelatedInformation relatedInformation) {
      super(relatedInformation.getMessage(), "Links to related Information: " + FileUtils.shortenFileUri(relatedInformation.getLocation().getUri()) + " " + FileUtils.positionToString(relatedInformation.getLocation().getRange().getStart()), null);
      loc = relatedInformation.getLocation();
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
    }
  }
}


