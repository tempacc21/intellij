package com.google.idea.sdkcompat.javascript;

import com.intellij.lang.typescript.tsconfig.TypeScriptConfig;

// #api261: TypeScriptConfig methods now take a boolean ts6orNewer parameter
public abstract class TypeScriptConfigAdapter extends BlazeTypeScriptConfigCompat {

  protected abstract boolean noImplicitAny();

  protected abstract boolean noImplicitThis();

  protected abstract boolean strictNullChecks();

  protected abstract boolean strictBindCallApply();

  protected abstract TypeScriptConfig.LanguageTarget getLanguageTarget();

  @Override
  protected final boolean noImplicitAnyImpl() {
    return noImplicitAny();
  }

  @Override
  protected final boolean noImplicitThisImpl() {
    return noImplicitThis();
  }

  @Override
  protected final boolean strictNullChecksImpl() {
    return strictNullChecks();
  }

  @Override
  protected final boolean strictBindCallApplyImpl() {
    return strictBindCallApply();
  }

  @Override
  public final TypeScriptConfig.LanguageTarget getLanguageTarget(boolean ts6orNewer) {
    return getLanguageTarget();
  }
}
