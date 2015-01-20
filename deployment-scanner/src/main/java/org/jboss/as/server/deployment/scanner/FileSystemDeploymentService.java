/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.deployment.scanner;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ARCHIVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CANCELLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PERSISTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELATIVE_TO;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLLBACK_ON_RUNTIME_FAILURE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.URL;
import static org.jboss.as.server.deployment.scanner.logging.DeploymentScannerLogger.ROOT_LOGGER;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.ControlledProcessStateService;
import org.jboss.as.controller.OperationFailedException;

import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.server.deployment.DeploymentAddHandler;
import org.jboss.as.server.deployment.DeploymentDeployHandler;
import org.jboss.as.server.deployment.DeploymentFullReplaceHandler;
import org.jboss.as.server.deployment.DeploymentRedeployHandler;
import org.jboss.as.server.deployment.DeploymentRemoveHandler;
import org.jboss.as.server.deployment.DeploymentUndeployHandler;
import org.jboss.as.server.deployment.scanner.ZipCompletionScanner.NonScannableZipException;
import org.jboss.as.server.deployment.scanner.api.DeploymentScanner;
import org.jboss.as.server.deployment.scanner.logging.DeploymentScannerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

/**
 * Service that monitors the filesystem for deployment content and if found deploys it.
 *
 * @author Brian Stansberry
 */
class FileSystemDeploymentService implements DeploymentScanner {

    static final Pattern ARCHIVE_PATTERN = Pattern.compile("^.*\\.(?:(?:[SsWwJjEeRr][Aa][Rr])|(?:[Ww][Aa][Bb])|(?:[Ee][Ss][Aa]))$");

    static final String DEPLOYED = ".deployed";
    static final String FAILED_DEPLOY = ".failed";
    static final String DO_DEPLOY = ".dodeploy";
    static final String DEPLOYING = ".isdeploying";
    static final String UNDEPLOYING = ".isundeploying";
    static final String UNDEPLOYED = ".undeployed";
    static final String SKIP_DEPLOY = ".skipdeploy";
    static final String PENDING = ".pending";

    static final String WEB_INF = "WEB-INF";
    static final String META_INF = "META-INF";

    /**
     * Max period an incomplete auto-deploy file can have no change in content
     */
    static final long MAX_NO_PROGRESS = 60000;

    /**
     * Default timeout for deployments to execute in seconds
     */
    static final long DEFAULT_DEPLOYMENT_TIMEOUT = 600;

    private File deploymentDir;
    private long scanInterval = 0;
    private volatile boolean scanEnabled = false;
    private volatile boolean firstScan = true;
    private ScheduledFuture<?> scanTask;
    private ScheduledFuture<?> rescanIncompleteTask;
    private ScheduledFuture<?> rescanUndeployTask;
    private final Lock scanLock = new ReentrantLock();

    private final Map<String, DeploymentMarker> deployed = new HashMap<String, DeploymentMarker>();
    private final HashSet<String> ignoredMissingDeployments = new HashSet<String>();
    private final HashSet<String> noticeLogged = new HashSet<String>();
    private final HashSet<String> illegalDirLogged = new HashSet<String>();
    private final HashSet<String> prematureExplodedContentDeletionLogged = new HashSet<String>();
    private final HashSet<File> nonscannableLogged = new HashSet<File>();
    private final Map<File, IncompleteDeploymentStatus> incompleteDeployments = new HashMap<File, IncompleteDeploymentStatus>();

    private final ScheduledExecutorService scheduledExecutor;
    private final ControlledProcessStateService processStateService;
    private volatile DeploymentOperations.Factory deploymentOperationsFactory;
    private volatile DeploymentOperations deploymentOperations;

    private FileFilter filter = new ExtensibleFilter();
    private volatile boolean autoDeployZip;
    private volatile boolean autoDeployExploded;
    private volatile boolean autoDeployXml;
    private volatile long maxNoProgress = MAX_NO_PROGRESS;
    private volatile boolean rollbackOnRuntimeFailure;
    private volatile long deploymentTimeout = DEFAULT_DEPLOYMENT_TIMEOUT;

    private final String relativeTo;
    private final String relativePath;
    private final PropertyChangeListener propertyChangeListener;

    private class DeploymentScanRunnable implements Runnable {

        @Override
        public void run() {
            try {
                scan();
            } catch (RejectedExecutionException e) {
                //Do nothing as this happens if a scan occurs during a reload of shutdown of a server.
            } catch (Exception e) {
                ROOT_LOGGER.scanException(e, deploymentDir.getAbsolutePath());
            }
        }
    }

    private final DeploymentScanRunnable scanRunnable = new DeploymentScanRunnable();

    FileSystemDeploymentService(final String relativeTo, final File deploymentDir, final File relativeToDir,
                                final DeploymentOperations.Factory deploymentOperationsFactory,
                                final ScheduledExecutorService scheduledExecutor,
                                final ControlledProcessStateService processStateService)
            throws OperationFailedException {
        if (scheduledExecutor == null) {
            throw DeploymentScannerLogger.ROOT_LOGGER.nullVar("scheduledExecutor");
        }
        if (deploymentDir == null) {
            throw DeploymentScannerLogger.ROOT_LOGGER.nullVar("deploymentDir");
        }
        if (!deploymentDir.exists()) {
            throw DeploymentScannerLogger.ROOT_LOGGER.directoryDoesNotExist(deploymentDir.getAbsolutePath());
        }
        if (!deploymentDir.isDirectory()) {
            throw DeploymentScannerLogger.ROOT_LOGGER.notADirectory(deploymentDir.getAbsolutePath());
        }
        if (!deploymentDir.canWrite()) {
            throw DeploymentScannerLogger.ROOT_LOGGER.directoryNotWritable(deploymentDir.getAbsolutePath());
        }
        this.relativeTo = relativeTo;
        this.deploymentDir = deploymentDir;
        this.deploymentOperationsFactory = deploymentOperationsFactory;
        this.scheduledExecutor = scheduledExecutor;
        this.processStateService = processStateService;
        if(processStateService != null) {
            this.propertyChangeListener = new PropertyChangeListener() {
                @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    if (ControlledProcessState.State.RUNNING == evt.getNewValue()) {
                         synchronized (this) {
                            if (scanEnabled) {
                                scheduledExecutor.execute(new UndeployScanRunnable());
                            }
                        }
                    } else if (ControlledProcessState.State.STOPPING == evt.getNewValue()) {
                        processStateService.removePropertyChangeListener(propertyChangeListener);
                    }
                }
            };
            this.processStateService.addPropertyChangeListener(propertyChangeListener);
        } else {
            this.propertyChangeListener = null;
        }
        if (relativeToDir != null) {
            String fullDir = deploymentDir.getAbsolutePath();
            String relDir = relativeToDir.getAbsolutePath();
            String sub = fullDir.substring(relDir.length());
            if (sub.startsWith(File.separator)) {
                sub = sub.length() == 1 ? "" : sub.substring(1);
            }
            this.relativePath = sub.length() > 0 ? sub + File.separator : sub;
        } else {
            relativePath = null;
        }
    }

    @Override
    public boolean isAutoDeployZippedContent() {
        return autoDeployZip;
    }

    @Override
    public void setAutoDeployZippedContent(boolean autoDeployZip) {
        this.autoDeployZip = autoDeployZip;
    }

    @Override
    public boolean isAutoDeployExplodedContent() {
        return autoDeployExploded;
    }

    @Override
    public void setAutoDeployExplodedContent(boolean autoDeployExploded) {
        if (autoDeployExploded && !this.autoDeployExploded) {
            ROOT_LOGGER.explodedAutoDeploymentContentWarning(DO_DEPLOY, CommonAttributes.AUTO_DEPLOY_EXPLODED);
        }
        this.autoDeployExploded = autoDeployExploded;
    }

    @Override
    public void setAutoDeployXMLContent(final boolean autoDeployXML) {
        this.autoDeployXml = autoDeployXML;
    }

    @Override
    public boolean isAutoDeployXMLContent() {
        return autoDeployXml;
    }

    @Override
    public boolean isEnabled() {
        return scanEnabled;
    }

    @Override
    public long getScanInterval() {
        return scanInterval;
    }

    @Override
    public synchronized void setScanInterval(long scanInterval) {
        if (scanInterval != this.scanInterval) {
            cancelScan();
        }
        this.scanInterval = scanInterval;
        startScan();
    }

    @Override
    public void setDeploymentTimeout(long deploymentTimeout) {
        this.deploymentTimeout = deploymentTimeout;
    }

    @Override
    public synchronized void startScanner() {
        assert deploymentOperationsFactory != null : "deploymentOperationsFactory is null";
        startScanner(deploymentOperationsFactory.create());
    }

    @Override
    public void setRuntimeFailureCausesRollback(boolean rollbackOnRuntimeFailure) {
        this.rollbackOnRuntimeFailure = rollbackOnRuntimeFailure;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void startScanner(final DeploymentOperations deploymentOperations) {
        this.deploymentOperations = deploymentOperations;
        final boolean scanEnabled = this.scanEnabled;
        if (scanEnabled) {
            return;
        }
        establishDeployedContentList(deploymentDir, deploymentOperations);
        this.scanEnabled = true;
        startScan();
        ROOT_LOGGER.started(getClass().getSimpleName(), deploymentDir.getAbsolutePath());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void stopScanner() {
        this.scanEnabled = false;
        cancelScan();
        safeClose(deploymentOperations);
        this.deploymentOperations = null;
    }

    /** Allow DeploymentScannerService to set the factory on the boot-time scanner */
    void setDeploymentOperationsFactory(final DeploymentOperations.Factory factory) {
        assert factory != null : "factory is null";
        this.deploymentOperationsFactory = factory;
    }

    /**
     * Hook solely for unit test to control how long deployments with no progress can exist without failing
     */
    void setMaxNoProgress(long max) {
        this.maxNoProgress = max;
    }

    private void establishDeployedContentList(File dir, final DeploymentOperations deploymentOperations) {
        final Set<String> deploymentNames = deploymentOperations.getDeploymentsStatus().keySet();
        final File[] children = listDirectoryChildren(dir);
        for (File child : children) {
            final String fileName = child.getName();
            if (child.isDirectory()) {
                if (!isEEArchive(fileName)) {
                    establishDeployedContentList(child, deploymentOperations);
                }
            } else if (fileName.endsWith(DEPLOYED)) {
                final String deploymentName = fileName.substring(0, fileName.length() - DEPLOYED.length());
                if (deploymentNames.contains(deploymentName)) {
                    File deployment = new File(dir, deploymentName);
                    deployed.put(deploymentName, new DeploymentMarker(child.lastModified(), !deployment.isDirectory(), dir));
                } else {
                    if (!child.delete()) {
                        ROOT_LOGGER.cannotRemoveDeploymentMarker(fileName);
                    }
                    // AS7-1130 Put down a marker so we deploy on first scan
                    File skipDeploy = new File(dir, deploymentName + SKIP_DEPLOY);
                    if (!skipDeploy.exists()) {
                        final File deployedMarker = new File(dir, deploymentName + DO_DEPLOY);
                        createMarkerFile(deployedMarker, deploymentName);
                    }
                }
            }
        }
    }

    /** Perform a one-off scan during boot to establish deployment tasks to execute during boot */
    void bootTimeScan(final DeploymentOperations deploymentOperations) {
        this.establishDeployedContentList(this.deploymentDir, deploymentOperations);
        if (acquireScanLock()) {
            try {
                scan(true, deploymentOperations);
            } finally {
                releaseScanLock();
            }
        }
    }

    /** Perform a normal scan */
    void scan() {
        if (acquireScanLock()) {
            ScanResult scanResult = null;
            try {
                scanResult = scan(false, deploymentOperations);
            } finally {
                try {
                    if (scanResult != null && scanResult.scheduleRescan) {
                        synchronized (this) {
                            if (scanEnabled) {
                                rescanIncompleteTask = scheduledExecutor.schedule(scanRunnable, 200, TimeUnit.MILLISECONDS);
                            }
                        }
                    }
                } finally {
                    releaseScanLock();
                }
            }
        }
    }

    /**
     * Perform a post-boot scan to remove any deployments added during boot that failed to deploy properly.
     * This method isn't private solely to allow a unit test in the same package to call it.
     */
    void forcedUndeployScan() {

        if (acquireScanLock()) {
            try {
                ROOT_LOGGER.tracef("Performing a post-boot forced undeploy scan for scan directory %s", deploymentDir.getAbsolutePath());
                ScanContext scanContext = new ScanContext(deploymentOperations);

                // Add remove actions to the plan for anything we count as
                // deployed that we didn't find on the scan
                for (Map.Entry<String, DeploymentMarker> missing : scanContext.toRemove.entrySet()) {
                    // remove successful deployment and left will be removed
                    if (scanContext.registeredDeployments.containsKey(missing.getKey())) {
                        scanContext.registeredDeployments.remove(missing.getKey());
                    }
                }
                Set<String> scannedDeployments = new HashSet<String>(scanContext.registeredDeployments.keySet());
                scannedDeployments.removeAll(scanContext.persistentDeployments);

                List<ScannerTask> scannerTasks = scanContext.scannerTasks;
                for (String toUndeploy : scannedDeployments) {
                    scannerTasks.add(new UndeployTask(toUndeploy, deploymentDir, scanContext.scanStartTime, true));
                }
                try {
                    executeScannerTasks(scannerTasks, deploymentOperations, true, new ScanResult());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                ROOT_LOGGER.tracef("Forced undeploy scan complete");
            } catch (Exception e) {
                ROOT_LOGGER.scanException(e, deploymentDir.getAbsolutePath());
            } finally {
                releaseScanLock();
            }
        }
    }

    private boolean acquireScanLock() {
        try {
            scanLock.lockInterruptibly();
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void releaseScanLock() {
        scanLock.unlock();
    }

    private ScanResult scan(boolean oneOffScan, final DeploymentOperations deploymentOperations) {

        ScanResult scanResult = new ScanResult();

        if (scanEnabled || oneOffScan) { // confirm the scan is still wanted
            ROOT_LOGGER.tracef("Scanning directory %s for deployment content changes", deploymentDir.getAbsolutePath());

            ScanContext scanContext = new ScanContext(deploymentOperations);

            scanDirectory(deploymentDir, relativePath, scanContext);

            // WARN about markers with no associated content. Do this first in case any auto-deploy issue
            // is due to a file that wasn't meant to be auto-deployed, but has a misspelled marker
            ignoredMissingDeployments.retainAll(scanContext.ignoredMissingDeployments);
            for (String deploymentName : scanContext.ignoredMissingDeployments) {
                if (ignoredMissingDeployments.add(deploymentName)) {
                    ROOT_LOGGER.deploymentNotFound(deploymentName);
                }
            }

            // Log INFO about non-auto-deploy files that have no marker files
            noticeLogged.retainAll(scanContext.nonDeployable);
            for (String fileName : scanContext.nonDeployable) {
                if (noticeLogged.add(fileName)) {
                    ROOT_LOGGER.deploymentTriggered(fileName, DO_DEPLOY);
                }
            }

            // Log ERROR about META-INF and WEB-INF dirs outside a deployment
            illegalDirLogged.retainAll(scanContext.illegalDir);
            for (String fileName : scanContext.illegalDir) {
                if (illegalDirLogged.add(fileName)) {
                    ROOT_LOGGER.invalidExplodedDeploymentDirectory(fileName, deploymentDir.getAbsolutePath());
                }
            }

            // Log about deleting exploded deployments without first triggering undeploy by deleting .deployed
            prematureExplodedContentDeletionLogged.retainAll(scanContext.prematureExplodedDeletions);
            for (String fileName : scanContext.prematureExplodedDeletions) {
                if (prematureExplodedContentDeletionLogged.add(fileName)) {
                    ROOT_LOGGER.explodedDeploymentContentDeleted(fileName, DEPLOYED);
                }
            }

            // Deal with any incomplete or non-scannable auto-deploy content
            ScanStatus status = handleAutoDeployFailures(scanContext);
            if (status != ScanStatus.PROCEED) {
                if (status == ScanStatus.RETRY && scanInterval > 1000) {
                    // schedule a non-repeating task to try again more quickly
                    scanResult.scheduleRescan = true;
                }
            } else {

                List<ScannerTask> scannerTasks = scanContext.scannerTasks;

                // Add remove actions to the plan for anything we count as
                // deployed that we didn't find on the scan
                for (Map.Entry<String, DeploymentMarker> missing : scanContext.toRemove.entrySet()) {
                    scannerTasks.add(new UndeployTask(missing.getKey(), missing.getValue().parentFolder, scanContext.scanStartTime, false));
                }
                try {
                    executeScannerTasks(scannerTasks, deploymentOperations, oneOffScan, scanResult);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ROOT_LOGGER.tracef("Scan complete");
                firstScan = false;
            }
        }

        return scanResult;
    }

    private void executeScannerTasks(List<ScannerTask> scannerTasks, DeploymentOperations deploymentOperations,
                                     boolean oneOffScan, ScanResult scanResult) throws InterruptedException {
        // Process the tasks
        if (scannerTasks.size() > 0) {
            List<ModelNode> updates = new ArrayList<ModelNode>(scannerTasks.size());

            for (ScannerTask task : scannerTasks) {
                task.recordInProgress(); // puts down .isdeploying, .isundeploying
                final ModelNode update = task.getUpdate();
                ROOT_LOGGER.infof("Deployment scan of [%s] found update action [%s]", deploymentDir, update);
                if (ROOT_LOGGER.isDebugEnabled()) {
                    ROOT_LOGGER.debugf("Deployment scan of [%s] found update action [%s]", deploymentDir, update);
                }
                updates.add(update);
            }

            boolean first = true;
            while (!updates.isEmpty() && (first || !oneOffScan)) {
                first = false;

                final Future<ModelNode> futureResults = deploymentOperations.deploy(getCompositeUpdate(updates), scheduledExecutor);
                final ModelNode results;
                try {
                    results = futureResults.get(deploymentTimeout, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    futureResults.cancel(true);
                    final ModelNode failure = new ModelNode();
                    failure.get(OUTCOME).set(FAILED);
                    failure.get(FAILURE_DESCRIPTION).set(DeploymentScannerLogger.ROOT_LOGGER.deploymentTimeout(deploymentTimeout));
                    for (ScannerTask task : scannerTasks) {
                        task.handleFailureResult(failure);
                    }
                    break;
                } catch (InterruptedException e) {
                    futureResults.cancel(true);
                    throw e;
                } catch (Exception e) {
                    ROOT_LOGGER.fileSystemDeploymentFailed(e);
                    futureResults.cancel(true);
                    final ModelNode failure = new ModelNode();
                    failure.get(OUTCOME).set(FAILED);
                    failure.get(FAILURE_DESCRIPTION).set(e.getMessage());
                    for (ScannerTask task : scannerTasks) {
                        task.handleFailureResult(failure);
                    }
                    break;
                }

                final List<ModelNode> toRetry = new ArrayList<ModelNode>();
                final List<ScannerTask> retryTasks = new ArrayList<ScannerTask>();
                if (results.hasDefined(RESULT)) {
                    final List<Property> resultList = results.get(RESULT).asPropertyList();
                    scanResult.requireUndeploy = false;
                    for (int i = 0; i < resultList.size(); i++) {
                        final ModelNode result = resultList.get(i).getValue();
                        final ScannerTask task = scannerTasks.get(i);
                        final ModelNode outcome = result.get(OUTCOME);
                        StringBuilder failureDesc = new StringBuilder();
                        if (outcome.isDefined() && SUCCESS.equals(outcome.asString()) && handleCompositeResult(result, failureDesc)){
                            task.handleSuccessResult();
                        } else if (outcome.isDefined() && CANCELLED.equals(outcome.asString())) {
                            toRetry.add(updates.get(i));
                            retryTasks.add(task);
                        } else {
                            if (failureDesc.length() > 0) {
                                result.get(FAILURE_DESCRIPTION).set(failureDesc.toString());
                                scanResult.requireUndeploy = true;
                            }
                            task.handleFailureResult(result);
                        }
                    }
                    updates = toRetry;
                    scannerTasks = retryTasks;
                } else {
                    for (ScannerTask current : scannerTasks) {
                        current.handleFailureResult(results);
                    }
                }
            }
        }
    }

    private class UndeployScanRunnable implements Runnable {

        @Override
        public void run() {
            forcedUndeployScan();
            processStateService.removePropertyChangeListener(propertyChangeListener);
        }
    }

    private boolean handleCompositeResult(ModelNode resultNode, StringBuilder failureDesc) {
        // WFLY-1305, regardless rollback-on-runtime-failure option, check each composite step result
        ModelNode outcome = resultNode.get(OUTCOME);
        boolean success = true;
        if (resultNode.get(OUTCOME).isDefined() && SUCCESS.equals(outcome.asString())) {
            if (resultNode.get(RESULT).isDefined()) {
                List<Property> results = resultNode.get(RESULT).asPropertyList();
                for (int i = 0; i < results.size(); i++) {
                    if (!handleCompositeResult(results.get(i).getValue(), failureDesc)) {
                        success = false;
                        break;
                    }
                }
            }
        } else {
            success = false;
            if (resultNode.get(FAILURE_DESCRIPTION).isDefined()) {
                failureDesc.append(resultNode.get(FAILURE_DESCRIPTION).toString());
            }
        }

        return success;
    }

    /**
     * Scan the given directory for content changes.
     *
     * @param directory   the directory to scan
     * @param scanContext context of the scan
     */
    private void scanDirectory(final File directory, final String relativePath, final ScanContext scanContext) {
        final File[] children = listDirectoryChildren(directory, filter);
        for (File child : children) {
            final String fileName = child.getName();
            if (fileName.endsWith(DEPLOYED)) {
                final String deploymentName = fileName.substring(0, fileName.length() - DEPLOYED.length());
                DeploymentMarker deploymentMarker = deployed.get(deploymentName);
                if (deploymentMarker == null) {
                    scanContext.toRemove.remove(deploymentName);
                    removeExtraneousMarker(child, fileName);
                } else {
                    final File deploymentFile = new File(directory, deploymentName);
                    if (deploymentFile.exists()) {
                        scanContext.toRemove.remove(deploymentName);
                        if (deployed.get(deploymentName).lastModified != child.lastModified()) {
                            scanContext.scannerTasks.add(new RedeployTask(deploymentName, child.lastModified(), directory,
                                    !child.isDirectory()));
                        } else {
                            // AS7-784 check for undeploy or removal of the deployment via another management client
                            Boolean isDeployed = scanContext.registeredDeployments.get(deploymentName);
                            if (isDeployed == null || !isDeployed) {
                                // It was undeployed or removed; get rid of the .deployed marker and
                                // put down a .undeployed marker so we don't process this again
                                deployed.remove(deploymentName);
                                removeExtraneousMarker(child, fileName);
                                final File marker = new File(directory, deploymentName + UNDEPLOYED);
                                createMarkerFile(marker, deploymentName);
                                if (isDeployed == null) {
                                    DeploymentScannerLogger.ROOT_LOGGER.scannerDeploymentRemovedButNotByScanner(deploymentName, marker);
                                } else {
                                    DeploymentScannerLogger.ROOT_LOGGER.scannerDeploymentUndeployedButNotByScanner(deploymentName, marker);
                                }
                            }
                        }
                    } else {
                        boolean autoDeployable = deploymentMarker.archive ? autoDeployZip : autoDeployExploded;
                        if (!autoDeployable) {
                            // Don't undeploy but log a warn if this is exploded content
                            scanContext.toRemove.remove(deploymentName);
                            if (!deploymentMarker.archive) {
                                scanContext.prematureExplodedDeletions.add(deploymentName);
                            }
                        }
                        // else AS7-1240 -- content is gone, leave deploymentName in scanContext.toRemove to trigger undeploy
                    }
                }
            } else if (fileName.endsWith(DO_DEPLOY) || (fileName.endsWith(FAILED_DEPLOY) && firstScan)) {
                // AS7-2581 - attempt to redeploy failed deployments on restart.
                final String markerStatus = fileName.endsWith(DO_DEPLOY) ? DO_DEPLOY : FAILED_DEPLOY;
                final String deploymentName = fileName.substring(0, fileName.length() - markerStatus.length());

                if (FAILED_DEPLOY.equals(markerStatus)) {
                    scanContext.reattemptFailedDeployments.add(deploymentName);
                    ROOT_LOGGER.reattemptingFailedDeployment(deploymentName);
                }

                final File deploymentFile = new File(directory, deploymentName);
                if (!deploymentFile.exists()) {
                    scanContext.ignoredMissingDeployments.add(deploymentName);
                    continue;
                }
                long timestamp = getDeploymentTimestamp(deploymentFile);
                final String path = relativeTo == null ? deploymentFile.getAbsolutePath() : relativePath + deploymentName; // TODO:
                // sub-directories
                // in
                // the
                // deploymentDir
                final boolean archive = deploymentFile.isFile();
                addContentAddingTask(path, archive, deploymentName, deploymentFile, timestamp, scanContext);
            } else if (fileName.endsWith(FAILED_DEPLOY)) {
                final String deploymentName = fileName.substring(0, fileName.length() - FAILED_DEPLOY.length());
                scanContext.toRemove.remove(deploymentName);
                if (!deployed.containsKey(deploymentName) && !(new File(child.getParent(), deploymentName).exists())) {
                    removeExtraneousMarker(child, fileName);
                }
            } else if (isEEArchive(fileName)) {
                boolean autoDeployable = child.isDirectory() ? autoDeployExploded : autoDeployZip;
                if (autoDeployable) {
                    if (!isAutoDeployDisabled(child)) {
                        long timestamp = getDeploymentTimestamp(child);
                        synchronizeScannerStatus(scanContext, directory, fileName, timestamp);
                        if (isFailedOrUndeployed(scanContext, directory, fileName, timestamp) || scanContext.reattemptFailedDeployments.contains(fileName)) {
                            continue;
                        }

                        DeploymentMarker marker = deployed.get(fileName);
                        if (marker == null || marker.lastModified != timestamp) {
                            try {
                                if (isZipComplete(child)) {
                                    final String path = relativeTo == null ? child.getAbsolutePath() : relativePath + fileName;
                                    final boolean archive = child.isFile();
                                    addContentAddingTask(path, archive, fileName, child, timestamp, scanContext);
                                } else {
                                    //we need to make sure that the file was not deleted while
                                    //the scanner was running
                                    if (child.exists()) {
                                        scanContext.incompleteFiles.put(child, new IncompleteDeploymentStatus(child, timestamp));
                                    }
                                }
                            } catch (NonScannableZipException e) {
                                // Track for possible logging in scan()
                                scanContext.nonscannable.put(child, new NonScannableStatus(e, timestamp));
                            }
                        }
                    }
                } else if (!deployed.containsKey(fileName) && !new File(fileName + DO_DEPLOY).exists()
                        && !new File(fileName + FAILED_DEPLOY).exists()) {
                    // Track for possible INFO logging of the need for a marker
                    scanContext.nonDeployable.add(fileName);
                }
            } else if (isXmlFile(fileName)) {
                if (autoDeployXml) {
                    if (!isAutoDeployDisabled(child)) {
                        long timestamp = getDeploymentTimestamp(child);
                        if (isFailedOrUndeployed(scanContext, directory, fileName, timestamp) || scanContext.reattemptFailedDeployments.contains(fileName)) {
                            continue;
                        }

                        DeploymentMarker marker = deployed.get(fileName);
                        if (marker == null || marker.lastModified != timestamp) {
                            if (isXmlComplete(child)) {
                                final String path = relativeTo == null ? child.getAbsolutePath() : relativePath + fileName;
                                addContentAddingTask(path, true, fileName, child, timestamp, scanContext);
                            } else {
                                //we need to make sure that the file was not deleted while
                                //the scanner was running
                                if (child.exists()) {
                                    scanContext.incompleteFiles.put(child, new IncompleteDeploymentStatus(child, timestamp));
                                }
                            }
                        }
                    }
                } else if (!deployed.containsKey(fileName) && !new File(fileName + DO_DEPLOY).exists()
                        && !new File(fileName + FAILED_DEPLOY).exists()) {
                    // Track for possible INFO logging of the need for a marker
                    scanContext.nonDeployable.add(fileName);
                }
            } else if (fileName.endsWith(DEPLOYING) || fileName.endsWith(UNDEPLOYING)) {
                // These markers should not survive a scan
                removeExtraneousMarker(child, fileName);
            } else if (fileName.endsWith(PENDING)) {
                // Do some housekeeping if the referenced deployment is gone
                final String deploymentName = fileName.substring(0, fileName.length() - PENDING.length());
                File deployment = new File(child.getParent(), deploymentName);
                if (!deployment.exists()) {
                    removeExtraneousMarker(child, fileName);
                }
            } else if (child.isDirectory()) { // exploded deployments would have been caught by isEEArchive(fileName) above

                if (WEB_INF.equalsIgnoreCase(fileName) || META_INF.equalsIgnoreCase(fileName)) {
                    // Looks like someone unzipped an archive in the scanned dir
                    // Track for possible ERROR logging
                    scanContext.illegalDir.add(fileName);
                } else {
                    scanDirectory(child, relativePath + child.getName() + File.separator, scanContext);
                }
            }
        }
    }

    private boolean isXmlComplete(final File xmlFile) {
        try {
            return XmlCompletionScanner.isCompleteDocument(xmlFile);
        } catch (Exception e) {
            ROOT_LOGGER.failedCheckingXMLFile(e, xmlFile.getPath());
            return false;
        }
    }

    private boolean isFailedOrUndeployed(final ScanContext scanContext, final File directory, final String fileName, final long timestamp) {
        final File failedMarker = new File(directory, fileName + FAILED_DEPLOY);
        if (failedMarker.exists() && timestamp <= failedMarker.lastModified()) {
            return true;
        }
        final File undeployedMarker = new File(directory, fileName + UNDEPLOYED);
        if (isMarkedUndeployed(undeployedMarker, timestamp) && !isRegisteredDeployment(scanContext, fileName)) {
            return true;
        }
        return false;
    }

    private void synchronizeScannerStatus(ScanContext scanContext, File directory, String fileName, long timestamp) {
        if (isRegisteredDeployment(scanContext, fileName)) {
            final File undeployedMarker = new File(directory, fileName + UNDEPLOYED);
            if (isMarkedUndeployed(undeployedMarker, timestamp) && !scanContext.persistentDeployments.contains(fileName)) {
                try {
                    ROOT_LOGGER.scannerDeploymentRedeployedButNotByScanner(fileName, undeployedMarker);
                    //We have a deployed app with an undeployed marker
                    undeployedMarker.delete();
                    final File deployedMarker = new File(directory, fileName + DEPLOYED);
                    deployedMarker.createNewFile();
                    boolean isArchive = false;
                    if (deployed.containsKey(fileName)) {
                        isArchive = deployed.get(fileName).archive;
                        deployedMarker.setLastModified(deployed.get(fileName).lastModified);
                    } else {
                        final File deploymentFile = new File(directory, fileName);
                        isArchive = deploymentFile.exists() && deploymentFile.isFile();
                        if(deploymentFile.exists()) {
                            deployedMarker.setLastModified(deploymentFile.lastModified());
                        }
                    }
                    deployed.put(fileName, new DeploymentMarker(deployedMarker.lastModified(), isArchive, directory));
                } catch (IOException ex) {
                    ROOT_LOGGER.failedStatusSynchronization(ex, fileName);
                }
            }
        }
    }

    private long addContentAddingTask(final String path, final boolean archive, final String deploymentName,
                                      final File deploymentFile, final long timestamp, final ScanContext scanContext) {
        if (scanContext.registeredDeployments.containsKey(deploymentName)) {
            scanContext.scannerTasks.add(new ReplaceTask(path, archive, deploymentName, deploymentFile, timestamp));
        } else {
            scanContext.scannerTasks.add(new DeployTask(path, archive, deploymentName, deploymentFile, timestamp));
        }
        scanContext.toRemove.remove(deploymentName);
        return timestamp;
    }

    private boolean isRegisteredDeployment(final ScanContext scanContext, final String fileName) {
        if(!scanContext.persistentDeployments.contains(fileName)) {//check that we are talking about the deployment in the scanned folder
            return scanContext.registeredDeployments.get(fileName) == null ? false : scanContext.registeredDeployments.get(fileName);
        }
        return false;
    }

    private boolean isMarkedUndeployed(final File undeployedMarker, final long timestamp) {
        return undeployedMarker.exists() && timestamp <= undeployedMarker.lastModified();
    }

    private boolean isZipComplete(File file) throws NonScannableZipException {
        if (file.isDirectory()) {
            for (File child : listDirectoryChildren(file)) {
                if (!isZipComplete(child)) {
                    return false;
                }
            }
            return true;
        } else if (isEEArchive(file.getName())) {
            try {
                return ZipCompletionScanner.isCompleteZip(file);
            } catch (IOException e) {
                ROOT_LOGGER.failedCheckingZipFile(e, file.getPath());
                return false;
            }
        } else {
            // A non-zip child
            return true;
        }
    }

    private boolean isAutoDeployDisabled(File file) {
        final File parent = file.getParentFile();
        final String name = file.getName();
        return new File(parent, name + SKIP_DEPLOY).exists() || new File(parent, name + DO_DEPLOY).exists();
    }

    private long getDeploymentTimestamp(File deploymentFile) {
        if (deploymentFile.isDirectory()) {
            // Scan for most recent file
            long latest = deploymentFile.lastModified();
            for (File child : listDirectoryChildren(deploymentFile)) {
                long childTimestamp = getDeploymentTimestamp(child);
                if (childTimestamp > latest) {
                    latest = childTimestamp;
                }
            }
            return latest;
        } else {
            return deploymentFile.lastModified();
        }
    }

    private boolean isEEArchive(String fileName) {
        return ARCHIVE_PATTERN.matcher(fileName).matches();
    }

    private boolean isXmlFile(String fileName) {
        return fileName.endsWith(".xml");
    }

    private void removeExtraneousMarker(File child, final String fileName) {
        if (!child.delete()) {
            ROOT_LOGGER.cannotRemoveDeploymentMarker(fileName);
        }
    }

    /**
     * Handle incompletely copied or non-scannable auto-deploy content and then abort scan
     *
     * @return true if the scan should be aborted
     */
    private ScanStatus handleAutoDeployFailures(ScanContext scanContext) {

        ScanStatus result = ScanStatus.PROCEED;
        boolean warnLogged = false;

        Set<File> noLongerIncomplete = new HashSet<File>(incompleteDeployments.keySet());
        noLongerIncomplete.removeAll(scanContext.incompleteFiles.keySet());

        int oldIncompleteCount = incompleteDeployments.size();
        incompleteDeployments.keySet().retainAll(scanContext.incompleteFiles.keySet());
        if (scanContext.incompleteFiles.size() > 0) {

            result = ScanStatus.RETRY;

            // If user dealt with some incomplete stuff but others remain, log everything again
            boolean logAll = incompleteDeployments.size() != oldIncompleteCount;

            long now = System.currentTimeMillis();
            for (Map.Entry<File, IncompleteDeploymentStatus> entry : scanContext.incompleteFiles.entrySet()) {
                File incompleteFile = entry.getKey();
                String deploymentName = incompleteFile.getName();
                IncompleteDeploymentStatus status = incompleteDeployments.get(incompleteFile);
                if (status == null || status.size < entry.getValue().size) {
                    status = entry.getValue();
                }

                if (now - status.timestamp > maxNoProgress) {
                    if (!status.warned) {
                        // Treat no progress for an extended period as a failed deployment
                        String suffix = deployed.containsKey(deploymentName) ? DeploymentScannerLogger.ROOT_LOGGER.previousContentDeployed() : "";
                        String msg = DeploymentScannerLogger.ROOT_LOGGER.deploymentContentIncomplete(incompleteFile, suffix);
                        writeFailedMarker(incompleteFile, msg, status.timestamp);
                        ROOT_LOGGER.error(msg);
                        status.warned = true;
                        warnLogged = true;

                        result = ScanStatus.ABORT;
                    }

                    // Clean up any .pending file
                    new File(incompleteFile.getParentFile(), deploymentName + PENDING).delete();
                } else {
                    boolean newIncomplete = incompleteDeployments.put(incompleteFile, status) == null;
                    if (newIncomplete || logAll) {
                        ROOT_LOGGER.incompleteContent(entry.getKey().getPath());
                    }
                    if (newIncomplete) {
                        File pending = new File(incompleteFile.getParentFile(), deploymentName + PENDING);
                        createMarkerFile(pending, deploymentName);
                    }
                }
            }
        }

        // Clean out any old "pending" files
        for (File complete : noLongerIncomplete) {
            File pending = new File(complete.getParentFile(), complete.getName() + PENDING);
            removeExtraneousMarker(pending, pending.getName());
        }

        int oldNonScannableCount = nonscannableLogged.size();
        nonscannableLogged.retainAll(scanContext.nonscannable.keySet());
        if (scanContext.nonscannable.size() > 0) {

            result = (result == ScanStatus.PROCEED ? ScanStatus.RETRY : result);

            // If user dealt with some nonscannable stuff but others remain, log everything again
            boolean logAll = nonscannableLogged.size() != oldNonScannableCount;

            for (Map.Entry<File, NonScannableStatus> entry : scanContext.nonscannable.entrySet()) {
                File nonScannable = entry.getKey();
                String fileName = nonScannable.getName();
                if (nonscannableLogged.add(nonScannable) || logAll) {
                    NonScannableStatus nonScannableStatus = entry.getValue();
                    NonScannableZipException e = nonScannableStatus.exception;
                    String msg = DeploymentScannerLogger.ROOT_LOGGER.unsafeAutoDeploy2(e.getLocalizedMessage(), fileName, DO_DEPLOY);
                    writeFailedMarker(nonScannable, msg, nonScannableStatus.timestamp);
                    ROOT_LOGGER.error(msg);
                    warnLogged = true;

                    result = ScanStatus.ABORT;
                }
            }
        }

        if (warnLogged) {

            Set<String> allProblems = new HashSet<String>();
            for (File f : scanContext.nonscannable.keySet()) {
                allProblems.add(f.getName());
            }
            for (File f : scanContext.incompleteFiles.keySet()) {
                allProblems.add(f.getName());
            }

            ROOT_LOGGER.unsafeAutoDeploy(DO_DEPLOY, SKIP_DEPLOY, allProblems);
        }

        return result;
    }

    private synchronized void startScan() {
        if (scanEnabled) {
            if (scanInterval > 0) {
                scanTask = scheduledExecutor.scheduleWithFixedDelay(scanRunnable, 0, scanInterval, TimeUnit.MILLISECONDS);
            } else {
                scanTask = scheduledExecutor.schedule(scanRunnable, scanInterval, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Invoke with the object monitor held
     */
    private void cancelScan() {
        if (rescanIncompleteTask != null) {
            rescanIncompleteTask.cancel(true);
            rescanIncompleteTask = null;
        }
        if (rescanUndeployTask != null) {
            rescanUndeployTask.cancel(true);
            rescanUndeployTask = null;
        }
        if (scanTask != null) {
            scanTask.cancel(true);
            scanTask = null;
        }
    }

    private ModelNode getCompositeUpdate(final List<ModelNode> updates) {
        final ModelNode op = Util.getEmptyOperation(COMPOSITE, new ModelNode());
        final ModelNode steps = op.get(STEPS);
        for (ModelNode update : updates) {
            steps.add(update);
        }
        op.get(OPERATION_HEADERS, ROLLBACK_ON_RUNTIME_FAILURE).set(rollbackOnRuntimeFailure);
        return op;
    }

    private ModelNode getCompositeUpdate(final ModelNode... updates) {
        final ModelNode op = Util.getEmptyOperation(COMPOSITE, new ModelNode());
        final ModelNode steps = op.get(STEPS);
        for (ModelNode update : updates) {
            steps.add(update);
        }
        op.get(OPERATION_HEADERS, ROLLBACK_ON_RUNTIME_FAILURE).set(rollbackOnRuntimeFailure);
        return op;
    }

    private void safeClose(final Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void createMarkerFile(final File marker, String deploymentName) {
        FileOutputStream fos = null;
        try {
            // marker.createNewFile(); - Don't create before the write as there is a potential race condition where
            // the file is deleted between the two calls.
            fos = new FileOutputStream(marker);
            fos.write(deploymentName.getBytes());
        } catch (IOException io) {
            ROOT_LOGGER.errorWritingDeploymentMarker(io, marker.getAbsolutePath());
        } finally {
            safeClose(fos);
        }
    }

    private void writeFailedMarker(final File deploymentFile, final String failureDescription, long failureTimestamp) {
        final File failedMarker = new File(deploymentFile.getParent(), deploymentFile.getName() + FAILED_DEPLOY);
        final File deployMarker = new File(deploymentFile.getParent(), deploymentFile.getName() + DO_DEPLOY);
        if (deployMarker.exists() && !deployMarker.delete()) {
            ROOT_LOGGER.cannotRemoveDeploymentMarker(deployMarker);
        }
        final File deployedMarker = new File(deploymentFile.getParent(), deploymentFile.getName() + DEPLOYED);
        if (deployedMarker.exists() && !deployedMarker.delete()) {
            ROOT_LOGGER.cannotRemoveDeploymentMarker(deployedMarker);
        }
        final File undeployedMarker = new File(deploymentFile.getParent(), deploymentFile.getName() + UNDEPLOYED);
        if (undeployedMarker.exists() && !undeployedMarker.delete()) {
            ROOT_LOGGER.cannotRemoveDeploymentMarker(undeployedMarker);
        }
        FileOutputStream fos = null;
        try {
            // failedMarker.createNewFile();
            fos = new FileOutputStream(failedMarker);
            fos.write(failureDescription.getBytes());
        } catch (IOException io) {
            ROOT_LOGGER.errorWritingDeploymentMarker(io, failedMarker.getAbsolutePath());
        } finally {
            safeClose(fos);
        }
    }

    private static File[] listDirectoryChildren(File directory) {
        return listDirectoryChildren(directory, null);
    }

    private static File[] listDirectoryChildren(File directory, FileFilter filter) {
        File[] result = directory.listFiles(filter);
        if (result == null) {
            throw DeploymentScannerLogger.ROOT_LOGGER.cannotListDirectoryFiles(directory);
        }
        return result;
    }

    private abstract class ScannerTask {
        protected final String deploymentName;
        protected final String parent;
        private final String inProgressMarkerSuffix;

        private ScannerTask(final String deploymentName, final File parent, final String inProgressMarkerSuffix) {
            this.deploymentName = deploymentName;
            this.parent = parent.getAbsolutePath();
            this.inProgressMarkerSuffix = inProgressMarkerSuffix;
            File marker = new File(parent, deploymentName + PENDING);
            if (!marker.exists()) {
                createMarkerFile(marker, deploymentName);
            }
        }

        protected void recordInProgress() {
            File marker = new File(parent, deploymentName + inProgressMarkerSuffix);
            createMarkerFile(marker, deploymentName);
            deleteUndeployedMarker();
            deletePendingMarker();
        }

        protected abstract ModelNode getUpdate();

        protected abstract void handleSuccessResult();

        protected abstract void handleFailureResult(final ModelNode result);

        protected void deletePendingMarker() {
            final File pendingMarker = new File(parent, deploymentName + PENDING);
            if (pendingMarker.exists() && !pendingMarker.delete()) {
                ROOT_LOGGER.cannotRemoveDeploymentMarker(pendingMarker);
            }
        }

        protected void deleteUndeployedMarker() {
            final File undeployedMarker = new File(parent, deploymentName + UNDEPLOYED);
            if (undeployedMarker.exists() && !undeployedMarker.delete()) {
                ROOT_LOGGER.cannotRemoveDeploymentMarker(undeployedMarker);
            }
        }

        protected void deleteDeployedMarker() {
            final File deployedMarker = new File(parent, deploymentName + DEPLOYED);
            if (deployedMarker.exists() && !deployedMarker.delete()) {
                ROOT_LOGGER.cannotRemoveDeploymentMarker(deployedMarker);
            }
        }

        protected void removeInProgressMarker() {
            File marker = new File(new File(parent), deploymentName + inProgressMarkerSuffix);
            if (marker.exists() && !marker.delete()) {
                ROOT_LOGGER.cannotDeleteDeploymentProgressMarker(marker);
            }
        }
    }

    private abstract class ContentAddingTask extends ScannerTask {
        private final String path;
        private final boolean archive;
        protected final File deploymentFile;
        protected final long doDeployTimestamp;

        protected ContentAddingTask(final String path, final boolean archive, final String deploymentName,
                                    final File deploymentFile, long markerTimestamp) {
            super(deploymentName, deploymentFile.getParentFile(), DEPLOYING);
            this.path = path;
            this.archive = archive;
            this.deploymentFile = deploymentFile;
            this.doDeployTimestamp = markerTimestamp;
        }

        protected ModelNode createContent() {
            final ModelNode content = new ModelNode();
            final ModelNode contentItem = content.get(0);
            if (archive) {
                try {
                    contentItem.get(URL).set(deploymentFile.toURI().toURL().toString());
                } catch (MalformedURLException ex) {
                }
            }
            if (!contentItem.hasDefined(URL)) {
                contentItem.get(ARCHIVE).set(archive);
                contentItem.get(PATH).set(path);
                if (relativeTo != null) {
                    contentItem.get(RELATIVE_TO).set(relativeTo);
                }
            }
            return content;
        }

        @Override
        protected void handleSuccessResult() {
            final File parentFolder = new File(parent);
            final File doDeployMarker = new File(parentFolder, deploymentFile.getName() + DO_DEPLOY);
            if (doDeployMarker.exists() && !doDeployMarker.delete()) {
                ROOT_LOGGER.cannotRemoveDeploymentMarker(doDeployMarker.getAbsolutePath());
            }

            // Remove any previous failure marker
            final File failedMarker = new File(deploymentFile.getParent(), deploymentFile.getName() + FAILED_DEPLOY);
            if (failedMarker.exists() && !failedMarker.delete()) {
                ROOT_LOGGER.cannotRemoveDeploymentMarker(failedMarker);
            }

            final File deployedMarker = new File(parent, deploymentFile.getName() + DEPLOYED);
            createMarkerFile(deployedMarker, deploymentName);
            deployedMarker.setLastModified(doDeployTimestamp);
            if (deployed.containsKey(deploymentName)) {
                deployed.remove(deploymentName);
            }
            deployed.put(deploymentName, new DeploymentMarker(doDeployTimestamp, archive, parentFolder));

            // Remove the in-progress marker - save this until the deployment is really complete.
            removeInProgressMarker();
        }
    }

    private final class DeployTask extends ContentAddingTask {
        private DeployTask(final String path, final boolean archive, final String deploymentName, final File deploymentFile,
                           long markerTimestamp) {
            super(path, archive, deploymentName, deploymentFile, markerTimestamp);
        }

        @Override
        protected ModelNode getUpdate() {
            final ModelNode address = new ModelNode().add(DEPLOYMENT, deploymentName);
            final ModelNode addOp = Util.getEmptyOperation(DeploymentAddHandler.OPERATION_NAME, address);
            addOp.get(CONTENT).set(createContent());
            addOp.get(PERSISTENT).set(false);
            final ModelNode deployOp = Util.getEmptyOperation(DeploymentDeployHandler.OPERATION_NAME, address);
            return getCompositeUpdate(addOp, deployOp);
        }

        @Override
        protected void handleFailureResult(final ModelNode result) {

            // Remove the in-progress marker
            removeInProgressMarker();

            writeFailedMarker(deploymentFile, result.get(FAILURE_DESCRIPTION).toString(), doDeployTimestamp);
        }
    }

    private final class ReplaceTask extends ContentAddingTask {
        private ReplaceTask(final String path, final boolean archive, String deploymentName, File deploymentFile,
                            long markerTimestamp) {
            super(path, archive, deploymentName, deploymentFile, markerTimestamp);
        }

        @Override
        protected ModelNode getUpdate() {
            final ModelNode replaceOp = Util.getEmptyOperation(DeploymentFullReplaceHandler.OPERATION_NAME, new ModelNode());
            replaceOp.get(NAME).set(deploymentName);
            replaceOp.get(CONTENT).set(createContent());
            replaceOp.get(PERSISTENT).set(false);
            replaceOp.get(ENABLED).set(true);
            return replaceOp;
        }

        @Override
        protected void handleFailureResult(ModelNode result) {

            // Remove the in-progress marker
            removeInProgressMarker();

            writeFailedMarker(deploymentFile, result.get(FAILURE_DESCRIPTION).toString(), doDeployTimestamp);
        }
    }

    private final class RedeployTask extends ScannerTask {
        private final long markerLastModified;
        private final boolean archive;

        private RedeployTask(final String deploymentName, final long markerLastModified, final File parent, boolean archive) {
            super(deploymentName, parent, DEPLOYING);
            this.markerLastModified = markerLastModified;
            this.archive = archive;
        }

        @Override
        protected ModelNode getUpdate() {
            final ModelNode address = new ModelNode().add(DEPLOYMENT, deploymentName);
            return Util.getEmptyOperation(DeploymentRedeployHandler.OPERATION_NAME, address);
        }

        @Override
        protected void handleSuccessResult() {

            // Remove the in-progress marker
            removeInProgressMarker();

            deployed.remove(deploymentName);
            deployed.put(deploymentName, new DeploymentMarker(markerLastModified, archive, new File(parent)));

        }

        @Override
        protected void handleFailureResult(ModelNode result) {

            // Remove the in-progress marker
            removeInProgressMarker();

            writeFailedMarker(new File(parent, deploymentName), result.get(FAILURE_DESCRIPTION).toString(), markerLastModified);
        }
    }

    private final class UndeployTask extends ScannerTask {

        private final long scanStartTime;
        private boolean forcedUndeploy;

        private UndeployTask(final String deploymentName, final File parent, final long scanStartTime, boolean forcedUndeploy) {
            super(deploymentName, parent, UNDEPLOYING);
            this.scanStartTime = scanStartTime;
            this.forcedUndeploy = forcedUndeploy;
        }

        @Override
        protected ModelNode getUpdate() {
            final ModelNode address = new ModelNode().add(DEPLOYMENT, deploymentName);
            final ModelNode undeployOp = Util.getEmptyOperation(DeploymentUndeployHandler.OPERATION_NAME, address);
            final ModelNode removeOp = Util.getEmptyOperation(DeploymentRemoveHandler.OPERATION_NAME, address);
            return getCompositeUpdate(undeployOp, removeOp);
        }

        @Override
        protected void handleSuccessResult() {

            // Remove the in-progress marker and any .deployed marker
            removeInProgressMarker();
            if (!forcedUndeploy) {
                deleteDeployedMarker();

                final File undeployedMarker = new File(parent, deploymentName + UNDEPLOYED);
                createMarkerFile(undeployedMarker, deploymentName);
                undeployedMarker.setLastModified(scanStartTime);
            }

            deployed.remove(deploymentName);
            noticeLogged.remove(deploymentName);
        }

        @Override
        protected void handleFailureResult(ModelNode result) {

            // Remove the in-progress marker
            removeInProgressMarker();

            if (!forcedUndeploy) {
                writeFailedMarker(new File(parent, deploymentName), result.get(FAILURE_DESCRIPTION).toString(), scanStartTime);
            }
        }
    }

    private class DeploymentMarker {
        private final long lastModified;
        private final boolean archive;
        private final File parentFolder;

        private DeploymentMarker(final long lastModified, boolean archive, File parentFolder) {
            this.lastModified = lastModified;
            this.archive = archive;
            this.parentFolder = parentFolder;
        }
    }

    private class ScanContext {
        /**
         * Existing deployments
         */
        private final Map<String, Boolean> registeredDeployments;
        /**
         * Existing persistent deployments
         */
        private final Set<String> persistentDeployments;
        /**
         * Tasks generated by the scan
         */
        private final List<ScannerTask> scannerTasks = new ArrayList<ScannerTask>();
        /**
         * Files to undeploy at the end of the scan
         */
        private final Map<String, DeploymentMarker> toRemove = new HashMap<String, DeploymentMarker>(deployed);
        /**
         * Marker files with no corresponding content
         */
        private final HashSet<String> ignoredMissingDeployments = new HashSet<String>();
        /**
         * Partially copied files detected by the scan
         */
        private Map<File, IncompleteDeploymentStatus> incompleteFiles = new HashMap<File, IncompleteDeploymentStatus>();
        /**
         * Non-auto-deployable files detected by the scan without an appropriate marker
         */
        private final HashSet<String> nonDeployable = new HashSet<String>();
        /**
         * WEB-INF and META-INF dirs not enclosed by a deployment
         */
        private final HashSet<String> illegalDir = new HashSet<String>();
        /**
         * Exploded deployment content removed without first removing the .deployed marker
         */
        private final HashSet<String> prematureExplodedDeletions = new HashSet<String>();
        /**
         * Failed deployments to reattempt in next firstScan during boot
         */
        private final HashSet<String> reattemptFailedDeployments = new HashSet<String>();
        /**
         * Auto-deployable files detected by the scan where ZipScanner threw a NonScannableZipException
         */
        private final Map<File, NonScannableStatus> nonscannable = new HashMap<File, NonScannableStatus>();
        /**
         * Timestamp when the scan started
         */
        private final long scanStartTime = System.currentTimeMillis();

        private ScanContext(final DeploymentOperations deploymentOperations) {
            registeredDeployments = deploymentOperations.getDeploymentsStatus();
            persistentDeployments = deploymentOperations.getPersistentDeployments();
        }
    }

    private static class IncompleteDeploymentStatus {
        private final long timestamp;
        private final long size;
        private boolean warned;

        IncompleteDeploymentStatus(final File file, final long timestamp) {
            this.size = file.length();
            this.timestamp = timestamp;
        }
    }

    private static class NonScannableStatus {
        private final long timestamp;
        private final NonScannableZipException exception;

        public NonScannableStatus(NonScannableZipException exception, long timestamp) {
            this.exception = exception;
            this.timestamp = timestamp;
        }
    }

    private static class ScanResult {
        private boolean scheduleRescan;
        private boolean requireUndeploy;
    }

    /**
     * Possible overall scan behaviors following return from handling auto-deploy failures
     */
    private enum ScanStatus {
        ABORT, RETRY, PROCEED
    }
}
