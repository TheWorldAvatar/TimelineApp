package uk.ac.cam.cares.jps.data.di;

import android.content.Context;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import uk.ac.cam.cares.jps.data.AppPreferenceRepository;
import uk.ac.cam.cares.jps.data.DatesWithTrajectoryRepository;
import uk.ac.cam.cares.jps.data.TrajectoryRepository;
import uk.ac.cam.cares.jps.login.LoginRepository;
import uk.ac.cam.cares.jps.network.DatesWithTrajectoryNetworkSource;
import uk.ac.cam.cares.jps.network.TrajectoryNetworkSource;
import uk.ac.cam.cares.jps.data.DatasetLayerRepository;
import uk.ac.cam.cares.jps.network.DatasetLayerNetworkSource;

import uk.ac.cam.cares.jps.data.TripAgentRepository;
import uk.ac.cam.cares.jps.network.TripAgentNetworkSource;

import uk.ac.cam.cares.jps.data.ExposureCalculationAgentRepository;
import uk.ac.cam.cares.jps.network.ExposureCalculationAgentNetworkSource;

import uk.ac.cam.cares.jps.data.ExposureFeatureInfoRepository;
import uk.ac.cam.cares.jps.network.ExposureFeatureInfoNetworkSource;

import uk.ac.cam.cares.jps.data.ExposureDatasetRepository;   
import uk.ac.cam.cares.jps.data.RoutingRepository;
import uk.ac.cam.cares.jps.network.OrsRoutingNetworkSource;


/**
 * Dependency injection specification for data module
 */
@Module
@InstallIn(SingletonComponent.class)
public class DataModule {
    @Provides
    @Singleton
    public TrajectoryRepository provideTrajectoryRepository(TrajectoryNetworkSource trajectoryNetworkSource,
                                                            LoginRepository loginRepository,
                                                            @ApplicationContext Context context) {
        return new TrajectoryRepository(trajectoryNetworkSource, loginRepository, context);
    }

    @Provides
    @Singleton
    public DatesWithTrajectoryRepository provideDatesWithTrajectoryRepository(DatesWithTrajectoryNetworkSource datesWithTrajectoryNetworkSource,
                                                                              LoginRepository loginRepository,
                                                                              @ApplicationContext Context context) {
        return new DatesWithTrajectoryRepository(datesWithTrajectoryNetworkSource, loginRepository, context);
    }

    @Provides
    @Singleton
    public AppPreferenceRepository provideAppPreferenceRepository(LoginRepository loginRepository,
                                                                        @ApplicationContext Context context) {
        return new AppPreferenceRepository(loginRepository, context);
    }

    @Provides
    @Singleton
    public DatasetLayerRepository provideDatasetLayerRepository(DatasetLayerNetworkSource datasetLayerNetworkSource,
                                                                LoginRepository loginRepository) {
        return new DatasetLayerRepository(datasetLayerNetworkSource, loginRepository);
    }

    @Provides
    @Singleton
    public TripAgentRepository provideTripAgentRepository(TripAgentNetworkSource tripAgentNetworkSource,
                                                            LoginRepository loginRepository,
                                                            @ApplicationContext Context context) {
        return new TripAgentRepository(tripAgentNetworkSource, loginRepository, context);
    }

    @Provides
    @Singleton
    public ExposureCalculationAgentRepository provideExposureCalculationAgentRepository(
            ExposureCalculationAgentNetworkSource exposureCalculationAgentNetworkSource,
            AppPreferenceRepository appPreferenceRepository,
            LoginRepository loginRepository) {
        return new ExposureCalculationAgentRepository(exposureCalculationAgentNetworkSource, appPreferenceRepository, loginRepository);
    }

    @Provides @Singleton
    public ExposureFeatureInfoRepository provideExposureFeatureInfoRepository(
            ExposureFeatureInfoNetworkSource networkSource, LoginRepository loginRepository) {
        return new ExposureFeatureInfoRepository(networkSource, loginRepository);
    }

    @Provides
    @Singleton
    public ExposureDatasetRepository provideExposureDatasetRepository(ExposureFeatureInfoNetworkSource networkSource,
                                                                        LoginRepository loginRepository) {
        return new ExposureDatasetRepository(networkSource, loginRepository);
    }

    @Provides
    @Singleton
    public RoutingRepository provideRoutingRepository(OrsRoutingNetworkSource orsRoutingNetworkSource) {
        return new RoutingRepository(orsRoutingNetworkSource);
    }
}
