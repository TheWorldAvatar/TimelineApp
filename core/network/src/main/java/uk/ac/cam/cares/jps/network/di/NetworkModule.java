package uk.ac.cam.cares.jps.network.di;

import android.content.Context;

import com.android.volley.RequestQueue;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import uk.ac.cam.cares.jps.network.DatesWithTrajectoryNetworkSource;
import uk.ac.cam.cares.jps.network.DatasetLayerNetworkSource;
import uk.ac.cam.cares.jps.network.TrajectoryNetworkSource;
import uk.ac.cam.cares.jps.network.TripAgentNetworkSource;

import uk.ac.cam.cares.jps.network.ExposureCalculationAgentNetworkSource;
import uk.ac.cam.cares.jps.network.ExposureFeatureInfoNetworkSource;

@Module
@InstallIn(SingletonComponent.class)
public class NetworkModule {
    @Provides
    @Singleton
    public TrajectoryNetworkSource provideTrajectoryNetworkSource(RequestQueue requestQueue, @ApplicationContext Context context) {
        return new TrajectoryNetworkSource(requestQueue, context);
    }

    @Provides
    @Singleton
    public DatesWithTrajectoryNetworkSource provideDatesWithTrajectoryNetworkSource(RequestQueue requestQueue, @ApplicationContext Context context) {
        return new DatesWithTrajectoryNetworkSource(requestQueue, context);
    }

    @Provides
    @Singleton
    public DatasetLayerNetworkSource provideDatasetLayerNetworkSource(RequestQueue requestQueue, @ApplicationContext Context context) {
        return new DatasetLayerNetworkSource(requestQueue, context);
    }

    @Provides
    @Singleton
    public TripAgentNetworkSource provideTripAgentNetworkSource(RequestQueue requestQueue, @ApplicationContext Context context) {
        return new TripAgentNetworkSource(requestQueue, context);
    }

    @Provides
    @Singleton
    public ExposureCalculationAgentNetworkSource provideExposureCalculationAgentNetworkSource(RequestQueue requestQueue, @ApplicationContext Context context) {
        return new ExposureCalculationAgentNetworkSource(requestQueue, context);
    }

    @Provides @Singleton
    public ExposureFeatureInfoNetworkSource provideExposureFeatureInfoNetworkSource(RequestQueue requestQueue, @ApplicationContext Context context) {
        return new ExposureFeatureInfoNetworkSource(requestQueue, context);
    }

}
