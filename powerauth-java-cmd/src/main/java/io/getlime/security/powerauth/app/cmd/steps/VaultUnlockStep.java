/*
 * Copyright 2016 Lime - HighTech Solutions s.r.o.
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
package io.getlime.security.powerauth.app.cmd.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import io.getlime.security.powerauth.app.cmd.logging.StepLogger;
import io.getlime.security.powerauth.app.cmd.util.EncryptedStorageUtil;
import io.getlime.security.powerauth.app.cmd.util.HttpUtil;
import io.getlime.security.powerauth.app.cmd.util.RestClientConfiguration;
import io.getlime.security.powerauth.crypto.client.keyfactory.PowerAuthClientKeyFactory;
import io.getlime.security.powerauth.crypto.client.signature.PowerAuthClientSignature;
import io.getlime.security.powerauth.crypto.client.vault.PowerAuthClientVault;
import io.getlime.security.powerauth.crypto.lib.config.PowerAuthConfiguration;
import io.getlime.security.powerauth.crypto.lib.enums.PowerAuthSignatureTypes;
import io.getlime.security.powerauth.crypto.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.http.PowerAuthHttpBody;
import io.getlime.security.powerauth.http.PowerAuthHttpHeader;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import io.getlime.security.powerauth.rest.api.model.base.PowerAuthApiResponse;
import io.getlime.security.powerauth.rest.api.model.response.VaultUnlockResponse;
import org.apache.commons.io.IOUtils;
import org.json.simple.JSONObject;

import javax.crypto.SecretKey;
import java.io.Console;
import java.io.FileWriter;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class with vault unlock logics.
 *
 * @author Petr Dvorak
 *
 */
public class VaultUnlockStep {

    private static final CryptoProviderUtil keyConversion = PowerAuthConfiguration.INSTANCE.getKeyConvertor();
    private static final KeyGenerator keyGenerator = new KeyGenerator();
    private static final PowerAuthClientSignature signature = new PowerAuthClientSignature();
    private static final PowerAuthClientKeyFactory keyFactory = new PowerAuthClientKeyFactory();
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Execute this step with given context
     * @param context Provided context
     * @return Result status object, null in case of failure.
     * @throws Exception In case of any error.
     */
    @SuppressWarnings("unchecked")
    public static JSONObject execute(Map<String, Object> context) throws Exception {

        // Read properties from "context"
        StepLogger stepLogger = (StepLogger) context.get("STEP_LOGGER");
        String uriString = (String) context.get("URI_STRING");
        JSONObject resultStatusObject = (JSONObject) context.get("STATUS_OBJECT");
        String statusFileName = (String) context.get("STATUS_FILENAME");
        String applicationKey = (String) context.get("APPLICATION_KEY");
        String applicationSecret = (String) context.get("APPLICATION_SECRET");
        String signatureType = (String) context.get("SIGNATURE_TYPE");
        String passwordProvided = (String) context.get("PASSWORD");

        stepLogger.writeItem(
                "Vault Unlock Started",
                null,
                "OK",
                null
        );

        // Prepare the activation URI
        String uri = uriString + "/pa/vault/unlock";

        // Get data from status
        String activationId = (String) resultStatusObject.get("activationId");
        long counter = (long) resultStatusObject.get("counter");
        byte[] signaturePossessionKeyBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("signaturePossessionKey"));
        byte[] signatureBiometryKeyBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("signatureBiometryKey"));
        byte[] signatureKnowledgeKeySalt = BaseEncoding.base64().decode((String) resultStatusObject.get("signatureKnowledgeKeySalt"));
        byte[] signatureKnowledgeKeyEncryptedBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("signatureKnowledgeKeyEncrypted"));
        byte[] transportMasterKeyBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("transportMasterKey"));
        byte[] encryptedDevicePrivateKeyBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("encryptedDevicePrivateKey"));
        byte[] serverPublicKeyBytes = BaseEncoding.base64().decode((String) resultStatusObject.get("serverPublicKey"));

        // Ask for the password to unlock knowledge factor key
        char[] password;
        if (passwordProvided == null) {
            Console console = System.console();
            password = console.readPassword("Enter your password to unlock the knowledge related key: ");
        } else {
            password = passwordProvided.toCharArray();
        }

        // Get the signature keys
        SecretKey signaturePossessionKey = keyConversion.convertBytesToSharedSecretKey(signaturePossessionKeyBytes);
        SecretKey signatureKnowledgeKey = EncryptedStorageUtil.getSignatureKnowledgeKey(password, signatureKnowledgeKeyEncryptedBytes, signatureKnowledgeKeySalt, keyGenerator);
        SecretKey signatureBiometryKey = keyConversion.convertBytesToSharedSecretKey(signatureBiometryKeyBytes);

        // Get the transport key
        SecretKey transportMasterKey = keyConversion.convertBytesToSharedSecretKey(transportMasterKeyBytes);

        // Generate nonce
        byte[] pa_nonce = keyGenerator.generateRandomBytes(16);

        // Compute the current PowerAuth 2.0 signature for possession and knowledge factor
        String signatureBaseString = PowerAuthHttpBody.getSignatureBaseString("post", "/pa/vault/unlock", pa_nonce, null) + "&" + applicationSecret;
        String pa_signature = signature.signatureForData(signatureBaseString.getBytes("UTF-8"), keyFactory.keysForSignatureType(signatureType, signaturePossessionKey, signatureKnowledgeKey, signatureBiometryKey), counter);
        String httpAuhtorizationHeader = PowerAuthHttpHeader.getPowerAuthSignatureHTTPHeader(activationId, applicationKey, BaseEncoding.base64().encode(pa_nonce), PowerAuthSignatureTypes.getEnumFromString(signatureType).toString(), pa_signature, "2.0");

        // Increment the counter
        counter += 1;
        resultStatusObject.put("counter", counter);

        // Store the activation status (updated counter)
        String formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultStatusObject);
        try (FileWriter file = new FileWriter(statusFileName)) {
            file.write(formatted);
        }

        // Call the server with activation data
        try {

            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
            headers.put(PowerAuthHttpHeader.HEADER_NAME, httpAuhtorizationHeader);

            stepLogger.writeServerCall(uri, "POST", null, headers);

            HttpResponse response = Unirest.post(uri)
                    .headers(headers)
                    .asString();

            TypeReference<PowerAuthApiResponse<VaultUnlockResponse>> typeReference = new TypeReference<PowerAuthApiResponse<VaultUnlockResponse>>() {};
            PowerAuthApiResponse<VaultUnlockResponse> responseWrapper = RestClientConfiguration
                    .defaultMapper()
                    .readValue(response.getRawBody(), typeReference);

            if (response.getStatus() == 200) {

                stepLogger.writeServerCallOK(responseWrapper, HttpUtil.flattenHttpHeaders(response.getHeaders()));

                VaultUnlockResponse responseObject = responseWrapper.getResponseObject();
                byte[] encryptedVaultEncryptionKey = BaseEncoding.base64().decode(responseObject.getEncryptedVaultEncryptionKey());

                PowerAuthClientVault vault = new PowerAuthClientVault();
                SecretKey vaultEncryptionKey = vault.decryptVaultEncryptionKey(encryptedVaultEncryptionKey, transportMasterKey, counter);
                PrivateKey devicePrivateKey = vault.decryptDevicePrivateKey(encryptedDevicePrivateKeyBytes, vaultEncryptionKey);
                PublicKey serverPublicKey = keyConversion.convertBytesToPublicKey(serverPublicKeyBytes);

                // Increment the counter
                counter += 1;
                resultStatusObject.put("counter", counter);

                // Store the activation status (updated counter)
                formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultStatusObject);
                try (FileWriter file = new FileWriter(statusFileName)) {
                    file.write(formatted);
                }

                SecretKey masterSecretKey = keyFactory.generateClientMasterSecretKey(devicePrivateKey, serverPublicKey);
                SecretKey transportKeyDeduced = keyFactory.generateServerTransportKey(masterSecretKey);
                boolean equal = transportKeyDeduced.equals(transportMasterKey);

                // Print the results
                Map<String, Object> objectMap = new HashMap<>();
                objectMap.put("activationId", activationId);
                objectMap.put("encryptedVaultEncryptionKey", BaseEncoding.base64().encode(encryptedVaultEncryptionKey));
                objectMap.put("transportMasterKey", BaseEncoding.base64().encode(keyConversion.convertSharedSecretKeyToBytes(transportMasterKey)));
                objectMap.put("vaultEncryptionKey", BaseEncoding.base64().encode(keyConversion.convertSharedSecretKeyToBytes(vaultEncryptionKey)));
                objectMap.put("devicePrivateKey", BaseEncoding.base64().encode(keyConversion.convertPrivateKeyToBytes(devicePrivateKey)));
                objectMap.put("privateKeyDecryptionSuccessful", (equal ? "true" : "false"));
                stepLogger.writeItem(
                        "Vault Unlocked",
                        "Secure vault was successfully unlocked",
                        "OK",
                        objectMap
                );
                stepLogger.writeDoneOK();
                return resultStatusObject;
            } else {

                // Increment the counter
                counter += 1;
                resultStatusObject.put("counter", counter);

                // Store the activation status (updated counter)
                formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultStatusObject);
                try (FileWriter file = new FileWriter(statusFileName)) {
                    file.write(formatted);
                }

                stepLogger.writeServerCallError(response.getStatus(), response.getBody(), HttpUtil.flattenHttpHeaders(response.getHeaders()));
                stepLogger.writeDoneFailed();
                System.exit(1);
            }


        } catch (UnirestException exception) {

            // Increment the counter, second time for vault unlock
            counter += 1;
            resultStatusObject.put("counter", counter);

            // Store the activation status (updated counter)
            formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultStatusObject);
            try (FileWriter file = new FileWriter(statusFileName)) {
                file.write(formatted);
            }

            stepLogger.writeServerCallConnectionError(exception);
            stepLogger.writeDoneFailed();
            System.exit(1);
        } catch (Exception exception) {

            // Increment the counter, second time for vault unlock
            counter += 1;
            resultStatusObject.put("counter", counter);

            // Store the activation status (updated counter)
            formatted = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultStatusObject);
            try (FileWriter file = new FileWriter(statusFileName)) {
                file.write(formatted);
            }

            stepLogger.writeError(exception);
            stepLogger.writeDoneFailed();
            System.exit(1);
        }
        return null;
    }

}
