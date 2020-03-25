package com.flutterwave.raveandroid.zmmobilemoney;

import android.content.Context;
import android.util.Log;

import com.flutterwave.raveandroid.DeviceIdGetter;
import com.flutterwave.raveandroid.PayloadBuilder;
import com.flutterwave.raveandroid.PayloadEncryptor;
import com.flutterwave.raveandroid.RavePayInitializer;
import com.flutterwave.raveandroid.Utils;
import com.flutterwave.raveandroid.ViewObject;
import com.flutterwave.raveandroid.data.EventLogger;
import com.flutterwave.raveandroid.data.events.ChargeAttemptEvent;
import com.flutterwave.raveandroid.data.events.Event;
import com.flutterwave.raveandroid.data.events.RequeryEvent;
import com.flutterwave.raveandroid.data.events.ScreenLaunchEvent;
import com.flutterwave.raveandroid.di.components.AppComponent;
import com.flutterwave.raveandroid.rave_java_commons.Payload;
import com.flutterwave.raveandroid.rave_java_commons.RaveConstants;
import com.flutterwave.raveandroid.rave_remote.Callbacks;
import com.flutterwave.raveandroid.rave_remote.FeeCheckRequestBody;
import com.flutterwave.raveandroid.rave_remote.NetworkRequestImpl;
import com.flutterwave.raveandroid.rave_remote.ResultCallback;
import com.flutterwave.raveandroid.rave_remote.requests.ChargeRequestBody;
import com.flutterwave.raveandroid.rave_remote.requests.RequeryRequestBody;
import com.flutterwave.raveandroid.rave_remote.responses.FeeCheckResponse;
import com.flutterwave.raveandroid.rave_remote.responses.MobileMoneyChargeResponse;
import com.flutterwave.raveandroid.rave_remote.responses.RequeryResponse;
import com.flutterwave.raveandroid.validators.AmountValidator;
import com.flutterwave.raveandroid.validators.NetworkValidator;
import com.flutterwave.raveandroid.validators.PhoneValidator;

import java.util.HashMap;

import javax.inject.Inject;

import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.RAVEPAY;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.fieldAmount;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.fieldNetwork;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.fieldPhone;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.fieldVoucher;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.noResponse;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.transactionError;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.validAmountPrompt;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.validNetworkPrompt;
import static com.flutterwave.raveandroid.rave_java_commons.RaveConstants.validPhonePrompt;

/**
 * Created by hfetuga on 28/06/2018.
 */

public class ZmMobileMoneyPresenter implements ZmMobileMoneyContract.UserActionsListener {
    @Inject
    NetworkRequestImpl networkRequest;
    @Inject
    AmountValidator amountValidator;
    @Inject
    PhoneValidator phoneValidator;
    @Inject
    NetworkValidator networkValidator;
    @Inject
    DeviceIdGetter deviceIdGetter;
    @Inject
    PayloadEncryptor payloadEncryptor;
    @Inject
    EventLogger eventLogger;
    private Context context;
    private ZmMobileMoneyContract.View mView;

    @Inject
    public ZmMobileMoneyPresenter(Context context, ZmMobileMoneyContract.View mView) {
        this.context = context;
        this.mView = mView;
    }

    public ZmMobileMoneyPresenter(Context context, ZmMobileMoneyContract.View mView, AppComponent appComponent){
        this.context = context;
        this.mView = mView;
        this.eventLogger = appComponent.eventLogger();
        this.amountValidator = appComponent.amountValidator();
        this.phoneValidator = appComponent.phoneValidator();
        this.networkValidator = appComponent.networkValidator();
        this.networkRequest = appComponent.networkImpl();
        this.deviceIdGetter = appComponent.deviceIdGetter();
        this.payloadEncryptor = appComponent.payloadEncryptor();
    }

    @Override
    public void fetchFee(final Payload payload) {
        FeeCheckRequestBody body = new FeeCheckRequestBody();
        body.setAmount(payload.getAmount());
        body.setCurrency(payload.getCurrency());
        body.setPtype("3");
        body.setPBFPubKey(payload.getPBFPubKey());

        mView.showProgressIndicator(true);

        networkRequest.getFee(body, new ResultCallback<FeeCheckResponse>() {
            @Override
            public void onSuccess(FeeCheckResponse response) {
                mView.showProgressIndicator(false);

                try {
                    mView.displayFee(response.getData().getCharge_amount(), payload);
                } catch (Exception e) {
                    e.printStackTrace();
                    mView.showFetchFeeFailed(transactionError);
                }
            }

            @Override
            public void onError(String message) {
                mView.showProgressIndicator(false);
                Log.e(RAVEPAY, message);
                mView.showFetchFeeFailed(message);
            }
        });
    }

    @Override
    public void chargeZmMobileMoney(final Payload payload, final String encryptionKey) {
        String cardRequestBodyAsString = Utils.convertChargeRequestPayloadToJson(payload);
        String encryptedCardRequestBody = payloadEncryptor.getEncryptedData(cardRequestBodyAsString, encryptionKey).trim().replaceAll("\\n", "");

        ChargeRequestBody body = new ChargeRequestBody();
        body.setAlg("3DES-24");
        body.setPBFPubKey(payload.getPBFPubKey());
        body.setClient(encryptedCardRequestBody);

        mView.showProgressIndicator(true);

        logEvent(new ChargeAttemptEvent("Zambia Mobile Money").getEvent(), payload.getPBFPubKey());


        networkRequest.chargeMobileMoneyWallet(body, new ResultCallback<MobileMoneyChargeResponse>() {
            @Override
            public void onSuccess(MobileMoneyChargeResponse response) {

                mView.showProgressIndicator(false);

                if (response.getData() != null) {
                    String flwRef = response.getData().getFlwRef();
                    String txRef = response.getData().getTx_ref();
                    requeryTx(flwRef, txRef, payload.getPBFPubKey());
                } else {
                    mView.onPaymentError(noResponse);
                }

            }

            @Override
            public void onError(String message) {
                mView.showProgressIndicator(false);
                mView.onPaymentError(message);
            }
        });
    }

    @Override
    public void requeryTx(final String flwRef, final String txRef, final String publicKey) {

        RequeryRequestBody body = new RequeryRequestBody();
        body.setFlw_ref(flwRef);
        body.setPBFPubKey(publicKey);

        mView.showPollingIndicator(true);

        logEvent(new RequeryEvent().getEvent(), publicKey);

        networkRequest.requeryTx(body, new Callbacks.OnRequeryRequestComplete() {
            @Override
            public void onSuccess(RequeryResponse response, String responseAsJSONString) {
                if (response.getData() == null) {
                    mView.onPaymentFailed(response.getStatus(), responseAsJSONString);
                } else if (response.getData().getChargeResponseCode().equals("02")) {
                    mView.onPollingRoundComplete(flwRef, txRef, publicKey);
                } else if (response.getData().getChargeResponseCode().equals("00")) {
                    mView.showPollingIndicator(false);
                    mView.onPaymentSuccessful(flwRef, txRef, responseAsJSONString);
                } else {
                    mView.showProgressIndicator(false);
                    mView.onPaymentFailed(response.getData().getStatus(), responseAsJSONString);
                }
            }

            @Override
            public void onError(String message, String responseAsJSONString) {
                mView.onPaymentFailed(message, responseAsJSONString);
            }
        });
    }


    @Override
    public void processTransaction(HashMap<String, ViewObject> dataHashMap, RavePayInitializer ravePayInitializer) {

        if (ravePayInitializer != null) {

            ravePayInitializer.setAmount(Double.parseDouble(dataHashMap.get(fieldAmount).getData()));

            PayloadBuilder builder = new PayloadBuilder();
            builder.setAmount(String.valueOf(ravePayInitializer.getAmount()))
                    .setCountry(ravePayInitializer.getCountry())
                    .setCurrency(ravePayInitializer.getCurrency())
                    .setEmail(ravePayInitializer.getEmail())
                    .setFirstname(ravePayInitializer.getfName())
                    .setLastname(ravePayInitializer.getlName())
                    .setIP(deviceIdGetter.getDeviceId())
                    .setTxRef(ravePayInitializer.getTxRef())
                    .setMeta(ravePayInitializer.getMeta())
                    .setSubAccount(ravePayInitializer.getSubAccount())
                    .setNetwork(dataHashMap.get(fieldNetwork).getData())
                    .setPhonenumber(dataHashMap.get(fieldPhone).getData())
                    .setPBFPubKey(ravePayInitializer.getPublicKey())
                    .setIsPreAuth(ravePayInitializer.getIsPreAuth())
                    .setDevice_fingerprint(deviceIdGetter.getDeviceId());

            if (dataHashMap.get(fieldVoucher) != null) {
                builder.setVoucher(dataHashMap.get(fieldVoucher).getData());
            }

            if (ravePayInitializer.getPayment_plan() != null) {
                builder.setPaymentPlan(ravePayInitializer.getPayment_plan());
            }

            Payload body = builder.createZmMobileMoneyPayload();

            if (ravePayInitializer.getIsDisplayFee()) {
                fetchFee(body);
            } else {
                chargeZmMobileMoney(body, ravePayInitializer.getEncryptionKey());
            }
        }
    }

    @Override
    public void onDataCollected(HashMap<String, ViewObject> dataHashMap) {

        boolean valid = true;

        int amountID = dataHashMap.get(fieldAmount).getViewId();
        String amount = dataHashMap.get(fieldAmount).getData();
        Class amountViewType = dataHashMap.get(fieldAmount).getViewType();

        int phoneID = dataHashMap.get(fieldPhone).getViewId();
        String phone = dataHashMap.get(fieldPhone).getData();
        Class phoneViewType = dataHashMap.get(fieldPhone).getViewType();

        String network = dataHashMap.get(fieldNetwork).getData();
        int networkPosition = Integer.valueOf(dataHashMap.get(RaveConstants.networkPosition).getData());

        boolean isAmountValidated = amountValidator.isAmountValid(amount);
        boolean isPhoneValid = phoneValidator.isPhoneValid(phone);
        boolean isNetworkValid = networkValidator.isNetworkValid(networkPosition);

        if (!isAmountValidated) {
            valid = false;
            mView.showFieldError(amountID, validAmountPrompt, amountViewType);
        }

        if (!isPhoneValid) {
            valid = false;
            mView.showFieldError(phoneID, validPhonePrompt, phoneViewType);
        }

        if (!isNetworkValid) {
            valid = false;
            mView.showToast(validNetworkPrompt);
        }

        if (valid) {
            mView.onValidationSuccessful(dataHashMap);
        }

    }

    @Override
    public void init(RavePayInitializer ravePayInitializer) {

        if (ravePayInitializer != null) {
            logEvent(new ScreenLaunchEvent("Zambia Mobile Money Fragment").getEvent(),
                    ravePayInitializer.getPublicKey());

            boolean isAmountValid = amountValidator.isAmountValid(ravePayInitializer.getAmount());
            if (isAmountValid) {
                mView.onAmountValidationSuccessful(String.valueOf(ravePayInitializer.getAmount()));
            }
        }
    }


    @Override
    public void onAttachView(ZmMobileMoneyContract.View view) {
        this.mView = view;
    }

    @Override
    public void onDetachView() {
        this.mView = new NullZmMobileMoneyView();
    }

    @Override
    public void logEvent(Event event, String publicKey) {
        eventLogger.logEvent(event, publicKey);
    }

}


