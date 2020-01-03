package com.cloudbees.jenkins.plugins.amazonecs;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class ECSProvisioningStrategy extends NodeProvisioner.Strategy {
    private static final Logger LOGGER = Logger.getLogger(ECSProvisioningStrategy.class.getName());

    @Nonnull
    @Override
    public NodeProvisioner.StrategyDecision apply(@Nonnull NodeProvisioner.StrategyState state) {
        LOGGER.log(Level.INFO, "Received StrategyState {0}", new Object[]{state});
        LoadStatistics.LoadStatisticsSnapshot snap = state.getSnapshot();
        Label label = state.getLabel();

        int excessWorkload = snap.getQueueLength() - snap.getAvailableExecutors() - snap.getConnectingExecutors();

        CLOUD:
        for (Cloud c : Jenkins.get().clouds) {
            if (excessWorkload <= 0) {
                break;  // enough agents allocated
            }

            // Make sure this cloud actually can provision for this label.
            if (!c.canProvision(label)) {
                continue;
            }

            for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                CauseOfBlockage causeOfBlockage = cl.canProvision(c, label, excessWorkload);
                if (causeOfBlockage != null) {
                    continue CLOUD;
                }
            }

            Collection<NodeProvisioner.PlannedNode> additionalCapacities = c.provision(label, excessWorkload);

            fireOnStarted(c, label, additionalCapacities);

            for (NodeProvisioner.PlannedNode ac : additionalCapacities) {
                excessWorkload -= ac.numExecutors;
                LOGGER.log(Level.INFO, "Started provisioning {0} from {1} with {2,number,integer} "
                                + "executors. Remaining excess workload: {3,number,#.###}",
                        new Object[]{ac.displayName, c.name, ac.numExecutors, excessWorkload});
            }
            state.recordPendingLaunches(additionalCapacities);
        }
        // we took action, only pass on to other strategies if our action was insufficient
        return excessWorkload > 0 ? NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES : NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
    }

    private static void fireOnStarted(final Cloud cloud, final Label label,
                                      final Collection<NodeProvisioner.PlannedNode> plannedNodes) {
        for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
            try {
                cl.onStarted(cloud, label, plannedNodes);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE, "Unexpected uncaught exception encountered while "
                        + "processing onStarted() listener call in " + cl + " for label "
                        + label.toString(), e);
            }
        }
    }
}
