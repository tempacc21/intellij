package com.google.idea.sdkcompat.ide;

import com.intellij.ide.wizard.AbstractWizard;
import com.intellij.ide.wizard.Step;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts.DialogTitle;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractWizardAdapter<T extends Step> extends AbstractWizard<T> {

  public AbstractWizardAdapter(@DialogTitle String title, @Nullable Project project) {
    super(title, project);
  }
}
