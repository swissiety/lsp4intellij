/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.lsp4intellij.client.languageserver;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;

/**
 * Class containing the options of the language server.
 */

public class ServerOptions {

    @NotNull
    private final TextDocumentSyncKind syncKind;
    @NotNull
    private final ServerCapabilities capabilities;
    private final CompletionOptions completionOptions;
    private final SignatureHelpOptions signatureHelpOptions;
    private final CodeLensOptions codeLensOptions;
    private final DocumentOnTypeFormattingOptions documentOnTypeFormattingOptions;
    private final DocumentLinkOptions documentLinkOptions;
    private final ExecuteCommandOptions executeCommandOptions;
    @Deprecated
    private final SemanticHighlightingServerCapabilities semanticHighlightingOptions;

    public ServerOptions(@NotNull ServerCapabilities serverCapabilities) {

        this.capabilities = serverCapabilities;

        final Either<TextDocumentSyncKind, TextDocumentSyncOptions> textDocumentSync = getCapabilities().getTextDocumentSync();
        if (textDocumentSync.isRight()) {
            this.syncKind = textDocumentSync.getRight().getChange();
        } else if (textDocumentSync.isLeft()) {
            this.syncKind = textDocumentSync.getLeft();
        } else {
            // if omitted it defaults to none
            this.syncKind = TextDocumentSyncKind.None;
        }

        this.completionOptions = getCapabilities().getCompletionProvider();
        this.signatureHelpOptions = getCapabilities().getSignatureHelpProvider();
        this.codeLensOptions = getCapabilities().getCodeLensProvider();
        this.documentOnTypeFormattingOptions = getCapabilities().getDocumentOnTypeFormattingProvider();
        this.documentLinkOptions = getCapabilities().getDocumentLinkProvider();
        this.executeCommandOptions = getCapabilities().getExecuteCommandProvider();
        this.semanticHighlightingOptions = getCapabilities().getSemanticHighlighting();
    }

    @NotNull
    public TextDocumentSyncKind getSyncKind() {
        return syncKind;
    }

    @NotNull
    public ServerCapabilities getCapabilities() {
        return capabilities;
    }

    public CompletionOptions getCompletionOptions() {
        return completionOptions;
    }

    public SignatureHelpOptions getSignatureHelpOptions() {
        return signatureHelpOptions;
    }

    public CodeLensOptions getCodeLensOptions() {
        return codeLensOptions;
    }

    public DocumentOnTypeFormattingOptions getDocumentOnTypeFormattingOptions() {
        return documentOnTypeFormattingOptions;
    }

    public DocumentLinkOptions getDocumentLinkOptions() {
        return documentLinkOptions;
    }

    public ExecuteCommandOptions getExecuteCommandOptions() {
        return executeCommandOptions;
    }

    @Deprecated
    public SemanticHighlightingServerCapabilities getSemanticHighlightingOptions() {
        return semanticHighlightingOptions;
    }
}
