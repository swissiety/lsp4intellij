package org.wso2.lsp4intellij.contributors.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.SymbolKind;
import org.jetbrains.annotations.NotNull;

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
}
