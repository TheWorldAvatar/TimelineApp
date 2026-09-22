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
import uk.ac.cam.cares.jps.data.HawkerCentreRepository;
import uk.ac.cam.cares.jps.network.HawkerCentreNetworkSource;

import uk.ac.cam.cares.jps.data.TripAgentRepository;
import uk.ac.cam.cares.jps.network.TripAgentNetworkSource;

import uk.ac.cam.cares.jps.data.ExposureCalculationAgentRepository;
import uk.ac.cam.cares.jps.network.ExposureCalculationAgentNetworkSource;

import uk.ac.cam.cares.jps.data.ExposureFeatureInfoRepository;
import uk.ac.cam.cares.jps.network.ExposureFeatureInfoNetworkSource;

import uk.ac.cam.cares.jps.data.ExposureDatasetRepository;      
import uk.ac.cam.cares.jps.network.BlazegraphNetworkSource;      

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
    public HawkerCentreRepository provideHawkerCentreRepository(HawkerCentreNetworkSource hawkerCentreNetworkSource,
                                                                LoginRepository loginRepository) {
        return new HawkerCentreRepository(hawkerCentreNetworkSource, loginRepository);
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
    public ExposureCalculationAgentRepository provideExposureCalculationAgentRepository(ExposureCalculationAgentNetworkSource exposureCalculationAgentNetworkSource,
                                                                                        AppPreferenceRepository appPreferenceRepository) {
        return new ExposureCalculationAgentRepository(exposureCalculationAgentNetworkSource, appPreferenceRepository);
    }

    @Provides @Singleton
    public ExposureFeatureInfoRepository provideExposureFeatureInfoRepository(
            ExposureFeatureInfoNetworkSource networkSource, LoginRepository loginRepository) {
        return new ExposureFeatureInfoRepository(networkSource, loginRepository);
    }

    @Provides
    @Singleton
    public ExposureDatasetRepository provideExposureDatasetRepository(BlazegraphNetworkSource networkSource) {
        return new ExposureDatasetRepository(networkSource);
    } 
}
