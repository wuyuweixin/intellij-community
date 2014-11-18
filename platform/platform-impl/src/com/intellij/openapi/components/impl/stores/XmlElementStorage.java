/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.openapi.components.impl.stores;

import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.StateStorageException;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.TrackingPathMacroSubstitutor;
import com.intellij.openapi.components.store.StateStorageBase;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashMap;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public abstract class XmlElementStorage extends StateStorageBase<StorageData> {
  @NotNull protected final String myRootElementName;
  protected StorageData myLoadedData;
  protected final StreamProvider myStreamProvider;
  protected final String myFileSpec;
  protected boolean myBlockSavingTheContent = false;

  protected final RoamingType myRoamingType;

  protected XmlElementStorage(@NotNull String fileSpec,
                              @Nullable RoamingType roamingType,
                              @Nullable TrackingPathMacroSubstitutor pathMacroSubstitutor,
                              @NotNull String rootElementName,
                              @Nullable StreamProvider streamProvider) {
    super(pathMacroSubstitutor);

    myFileSpec = fileSpec;
    myRoamingType = roamingType == null ? RoamingType.PER_USER : roamingType;
    myRootElementName = rootElementName;
    myStreamProvider = myRoamingType == RoamingType.DISABLED ? null : streamProvider;
  }

  @Nullable
  protected abstract Element loadLocalData();


  @Nullable
  @Override
  protected Element getStateAndArchive(@NotNull StorageData storageData, @NotNull String componentName) {
    return storageData.getStateAndArchive(componentName);
  }

  @Override
  @NotNull
  protected StorageData getStorageData(boolean reloadData) {
    if (myLoadedData != null && !reloadData) {
      return myLoadedData;
    }

    myLoadedData = loadData(true);
    return myLoadedData;
  }

  @NotNull
  protected StorageData loadData(boolean useProvidersData) {
    StorageData result = createStorageData();

    if (useProvidersData && myStreamProvider != null && myStreamProvider.isEnabled()) {
      try {
        Element element = loadDataFromStreamProvider();
        if (element != null) {
          loadState(result, element);
        }

        // we don't use local data if has stream provider
        return result;
      }
      catch (Exception e) {
        LOG.warn(e);
      }
    }

    Element element = loadLocalData();
    if (element != null) {
      loadState(result, element);
    }

    return result;
  }

  @Nullable
  protected final Element loadDataFromStreamProvider() throws IOException, JDOMException {
    assert myStreamProvider != null;
    InputStream inputStream = myStreamProvider.loadContent(myFileSpec, myRoamingType);
    return inputStream == null ? null : JDOMUtil.loadDocument(inputStream).detachRootElement();
  }

  protected final void loadState(@NotNull StorageData result, @NotNull Element element) {
    result.load(element, myPathMacroSubstitutor, true);
  }

  @NotNull
  protected StorageData createStorageData() {
    return new StorageData(myRootElementName);
  }

  public void setDefaultState(final Element element) {
    myLoadedData = createStorageData();
    loadState(myLoadedData, element);
  }

  @Override
  @Nullable
  public final ExternalizationSession startExternalization() {
    return checkIsSavingDisabled() ? null : createSaveSession(getStorageData());
  }

  protected abstract XmlElementStorageSaveSession createSaveSession(@NotNull StorageData storageData);

  @Nullable
  protected final Element getElement(@NotNull StorageData data, boolean collapsePaths, @NotNull Map<String, Element> newLiveStates) {
    Element element = data.save(newLiveStates);
    if (element == null || JDOMUtil.isEmpty(element)) {
      return null;
    }

    if (collapsePaths && myPathMacroSubstitutor != null) {
      try {
        myPathMacroSubstitutor.collapsePaths(element);
      }
      finally {
        myPathMacroSubstitutor.reset();
      }
    }

    return element;
  }

  @Override
  public void analyzeExternalChangesAndUpdateIfNeed(@NotNull Collection<VirtualFile> changedFiles, @NotNull Set<String> result) {
    StorageData oldData = myLoadedData;
    StorageData newData = getStorageData(true);
    if (oldData == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("analyzeExternalChangesAndUpdateIfNeed: old data null, load new for " + toString());
      }
      result.addAll(newData.getComponentNames());
    }
    else {
      Set<String> changedComponentNames = oldData.getChangedComponentNames(newData, myPathMacroSubstitutor);
      if (LOG.isDebugEnabled()) {
        LOG.debug("analyzeExternalChangesAndUpdateIfNeed: changedComponentNames + " + changedComponentNames + " for " + toString());
      }
      if (!ContainerUtil.isEmpty(changedComponentNames)) {
        result.addAll(changedComponentNames);
      }
    }
  }

  protected abstract class XmlElementStorageSaveSession implements SaveSession, ExternalizationSession {
    private final StorageData myOriginalStorageData;
    private StorageData myCopiedStorageData;

    private final Map<String, Element> myNewLiveStates = new THashMap<String, Element>();

    public XmlElementStorageSaveSession(@NotNull StorageData storageData) {
      myOriginalStorageData = storageData;
    }

    @Nullable
    @Override
    public final SaveSession createSaveSession() {
      return checkIsSavingDisabled() || myCopiedStorageData == null ? null : this;
    }

    @Override
    public final void setState(@NotNull Object component, @NotNull String componentName, @NotNull Object state, @Nullable Storage storageSpec) {
      Element element;
      try {
        element = DefaultStateSerializer.serializeState(state, storageSpec);
      }
      catch (WriteExternalException e) {
        LOG.debug(e);
        return;
      }
      catch (Throwable e) {
        LOG.error("Unable to serialize " + componentName + " state", e);
        return;
      }

      if (myCopiedStorageData == null) {
        myCopiedStorageData = StorageData.setStateAndCloneIfNeed(componentName, element, myOriginalStorageData, myNewLiveStates);
      }
      else {
        myCopiedStorageData.setState(componentName, element, myNewLiveStates);
      }
    }

    @Override
    public final void save() {
      if (myBlockSavingTheContent) {
        return;
      }

      try {
        doSave(getElement(myCopiedStorageData, isCollapsePathsOnSave(), myNewLiveStates));
        myLoadedData = myCopiedStorageData;
      }
      catch (IOException e) {
        throw new StateStorageException(e);
      }
    }

    // only because default project store hack
    protected boolean isCollapsePathsOnSave() {
      return true;
    }

    protected abstract void doSave(@Nullable Element element) throws IOException;

    protected void saveForProvider(@Nullable BufferExposingByteArrayOutputStream content, @Nullable Element element) throws IOException {
      if (!myStreamProvider.isApplicable(myFileSpec, myRoamingType)) {
        return;
      }

      if (element == null) {
        myStreamProvider.delete(myFileSpec, myRoamingType);
      }
      else {
        doSaveForProvider(element, myRoamingType, content);
      }
    }

    private void doSaveForProvider(@NotNull Element element, @NotNull RoamingType roamingType, @Nullable BufferExposingByteArrayOutputStream content) throws IOException {
      if (content == null) {
        StorageUtil.sendContent(myStreamProvider, myFileSpec, element, roamingType, true);
      }
      else {
        myStreamProvider.saveContent(myFileSpec, content.getInternalBuffer(), content.size(), myRoamingType, true);
      }
    }
  }

  @TestOnly
  public void resetData() {
    myLoadedData = null;
  }
}
