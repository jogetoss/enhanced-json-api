package org.joget.marketplace;

import java.util.ArrayList;
import java.util.Collection;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {

    public static final String VERSION = "8.0.1";

    protected Collection<ServiceRegistration> registrationList;

    public void start(BundleContext context) {
        registrationList = new ArrayList<ServiceRegistration>();

        //Register plugin here
        registrationList.add(context.registerService(EnhancedJsonTool.class.getName(), new EnhancedJsonTool(), null));
        registrationList.add(context.registerService(EnhancedJsonApiFormLoadBinder.class.getName(), new EnhancedJsonApiFormLoadBinder(), null));
        registrationList.add(context.registerService(EnhancedJsonApiFormOptionsBinder.class.getName(), new EnhancedJsonApiFormOptionsBinder(), null));
        registrationList.add(context.registerService(EnhancedJsonApiFormStoreBinder.class.getName(), new EnhancedJsonApiFormStoreBinder(), null));
        registrationList.add(context.registerService(EnhancedJsonApiDatalistBinder.class.getName(), new EnhancedJsonApiDatalistBinder(), null));
    }

    public void stop(BundleContext context) {
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}