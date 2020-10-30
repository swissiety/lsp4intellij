package org.wso2.lsp4intellij.contributors.psi;

import com.intellij.icons.AllIcons;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.SymbolKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class LSPPsiSymbol extends LSPPsiElement{
  private final SymbolKind kind;

  /**
   * @param name    The name (text) of the element
   * @param project The project it belongs to
   * @param start   The offset in the editor where the element starts
   * @param end     The offset where it ends
   * @param file
   */
  public LSPPsiSymbol(SymbolKind kind, String name, @NotNull Project project, int start, int end, PsiFile file) {
    super(name, project, start, end, file);
    this.kind = kind;
  }

  @Override
  public ItemPresentation getPresentation() {
    return new ItemPresentation() {
      @Override
      public @Nullable String getPresentableText() {
        return getName();
      }

      @Override
      public @Nullable String getLocationString() {
        return "";
      }

      @Override
      public @Nullable Icon getIcon(boolean unused) {
          switch (kind){
            case Class:
              return AllIcons.Nodes.Class;
            case Interface:
              return AllIcons.Nodes.Interface;
            case Field:
            case Constant:
              return AllIcons.Nodes.Field;
            case Method:
            case Constructor:
              return AllIcons.Nodes.Method;
          }
          return AllIcons.Nodes.AnonymousClass;
      }
    };
  }
}
