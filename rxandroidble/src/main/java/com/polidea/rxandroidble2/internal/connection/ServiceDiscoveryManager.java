package com.polidea.rxandroidble2.internal.connection;


import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;

import androidx.annotation.NonNull;

import com.polidea.rxandroidble2.RxBleCustomOperation;
import com.polidea.rxandroidble2.RxBleDeviceServices;
import com.polidea.rxandroidble2.internal.operations.OperationsProvider;
import com.polidea.rxandroidble2.internal.operations.ServiceDiscoveryOperation;
import com.polidea.rxandroidble2.internal.operations.TimeoutConfiguration;
import com.polidea.rxandroidble2.internal.serialization.ConnectionOperationQueue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import bleshadow.javax.inject.Inject;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.internal.functions.Functions;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

@ConnectionScope
class ServiceDiscoveryManager {

    final ConnectionOperationQueue operationQueue;
    final BluetoothGatt bluetoothGatt;
    final OperationsProvider operationProvider;
    private Single<RxBleDeviceServices> deviceServicesObservable;
    final Subject<TimeoutConfiguration> timeoutBehaviorSubject = BehaviorSubject.<TimeoutConfiguration>create().toSerialized();
    boolean hasCachedResults = false;

    @Inject
    ServiceDiscoveryManager(ConnectionOperationQueue operationQueue, BluetoothGatt bluetoothGatt, OperationsProvider operationProvider) {
        this.operationQueue = operationQueue;
        this.bluetoothGatt = bluetoothGatt;
        this.operationProvider = operationProvider;
        reset();
    }

    public boolean refreshGatt() {
        try {
            Method bluetoothGattRefreshFunction = bluetoothGatt.getClass().getMethod("refresh");
            boolean success = (Boolean) bluetoothGattRefreshFunction.invoke(bluetoothGatt);
            return success;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    Single<RxBleDeviceServices> getDiscoverServicesSingle(final long timeout, final TimeUnit timeoutTimeUnit) {
        return getDiscoverServicesSingle(timeout, timeoutTimeUnit, false);
    }

    Single<RxBleDeviceServices> getDiscoverServicesSingle(final long timeout, final TimeUnit timeoutTimeUnit, Boolean clearCache) {
        if (clearCache) {
            hasCachedResults = false;
            this.deviceServicesObservable = getTimeoutConfiguration().flatMap(scheduleActualDiscoveryWithTimeout())
                    .doOnSuccess(Functions.actionConsumer(new Action() {
                        @Override
                        public void run() throws Exception {
                            hasCachedResults = true;
                        }
                    }))
                    .doOnError(Functions.actionConsumer(new Action() {
                        @Override
                        public void run() {
                            reset();
                        }
                    }))
                    .cache();
        }
        if (hasCachedResults && !clearCache) {
            // optimisation to decrease the number of allocations
            return deviceServicesObservable;
        } else {
            return deviceServicesObservable.doOnSubscribe(
                    new Consumer<Disposable>() {
                        @Override
                        public void accept(Disposable disposable) {
                            timeoutBehaviorSubject.onNext(new TimeoutConfiguration(timeout, timeoutTimeUnit, Schedulers.computation()));
                        }
                    });
        }
    }

    void reset() {
        hasCachedResults = false;
        this.deviceServicesObservable = getListOfServicesFromGatt()
                .map(wrapIntoRxBleDeviceServices())
                .switchIfEmpty(getTimeoutConfiguration().flatMap(scheduleActualDiscoveryWithTimeout()))
                .doOnSuccess(Functions.actionConsumer(new Action() {
                    @Override
                    public void run() {
                        hasCachedResults = true;
                    }
                }))
                .doOnError(Functions.actionConsumer(new Action() {
                    @Override
                    public void run() {
                        reset();
                    }
                }))
                .cache();
    }

    @NonNull
    private static Function<List<BluetoothGattService>, RxBleDeviceServices> wrapIntoRxBleDeviceServices() {
        return new Function<List<BluetoothGattService>, RxBleDeviceServices>() {
            @Override
            public RxBleDeviceServices apply(List<BluetoothGattService> bluetoothGattServices) {
                return new RxBleDeviceServices(bluetoothGattServices);
            }
        };
    }

    private Maybe<List<BluetoothGattService>> getListOfServicesFromGatt() {
        return Single.fromCallable(new Callable<List<BluetoothGattService>>() {
                    @Override
                    public List<BluetoothGattService> call() {
                        return bluetoothGatt.getServices();
                    }
                })
                .filter(new Predicate<List<BluetoothGattService>>() {
                    @Override
                    public boolean test(List<BluetoothGattService> bluetoothGattServices) {
                        return bluetoothGattServices.size() > 0;
                    }
                });
    }

    @NonNull
    private Single<TimeoutConfiguration> getTimeoutConfiguration() {
        return timeoutBehaviorSubject.firstOrError();
    }

    @NonNull
    private Function<TimeoutConfiguration, Single<RxBleDeviceServices>> scheduleActualDiscoveryWithTimeout() {
        return new Function<TimeoutConfiguration, Single<RxBleDeviceServices>>() {
            @Override
            public Single<RxBleDeviceServices> apply(TimeoutConfiguration timeoutConf) {
                final ServiceDiscoveryOperation operation = operationProvider
                        .provideServiceDiscoveryOperation(timeoutConf.timeout, timeoutConf.timeoutTimeUnit);
                return operationQueue.queue(operation)
                        .firstOrError();
            }
        };
    }
}
