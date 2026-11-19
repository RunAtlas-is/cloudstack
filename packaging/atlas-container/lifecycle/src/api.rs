// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

//! Signed calls to the CloudStack API of the local management server.

use std::time::Duration;

use base64::Engine;
use hmac::{Hmac, Mac};
use percent_encoding::{utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};
use sha1::Sha1;

/// The characters CloudStack leaves unencoded when it signs: those of RFC 3986.
const QUERY: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'_').remove(b'.').remove(b'~');
const API_TIMEOUT: Duration = Duration::from_secs(10);

pub struct ManagementServer {
    pub id: String,
    pub state: String,
    pub service_ip: String,
}

pub struct Api {
    url: String,
    api_key: String,
    secret_key: String,
    agent: ureq::Agent,
}

/// The query string of a call, its parameters sorted by name and their values encoded.
pub fn query(params: &[(&str, &str)]) -> String {
    let mut sorted: Vec<_> = params.to_vec();
    sorted.sort_by_key(|(name, _)| name.to_lowercase());
    sorted
        .iter()
        .map(|(name, value)| format!("{name}={}", utf8_percent_encode(value, QUERY)))
        .collect::<Vec<_>>()
        .join("&")
}

/// CloudStack's signature: HMAC-SHA1 over the lower-cased query string, in Base64.
pub fn signature(query: &str, secret_key: &str) -> String {
    let mut mac = Hmac::<Sha1>::new_from_slice(secret_key.as_bytes()).expect("HMAC takes a key of any length");
    mac.update(query.to_lowercase().as_bytes());
    base64::engine::general_purpose::STANDARD.encode(mac.finalize().into_bytes())
}

impl Api {
    pub fn new(url: &str, api_key: String, secret_key: String) -> Self {
        Api { url: url.to_string(), api_key, secret_key, agent: ureq::AgentBuilder::new().timeout(API_TIMEOUT).build() }
    }

    fn call(&self, command: &str, params: &[(&str, &str)]) -> Result<serde_json::Value, String> {
        let mut all = vec![("command", command), ("response", "json"), ("apiKey", self.api_key.as_str())];
        all.extend_from_slice(params);
        let query = query(&all);
        let signature = signature(&query, &self.secret_key);
        let url = format!("{}?{query}&signature={}", self.url, utf8_percent_encode(&signature, QUERY));
        // The error names only the command and the status, never the URL, which carries the key.
        match self.agent.get(&url).call() {
            Ok(response) => response.into_json().map_err(|e| format!("{command}: unreadable answer: {e}")),
            Err(ureq::Error::Status(code, _)) => Err(format!("{command}: HTTP {code}")),
            Err(_) => Err(format!("{command}: no answer")),
        }
    }

    pub fn management_servers(&self) -> Result<Vec<ManagementServer>, String> {
        let answer = self.call("listManagementServers", &[])?;
        Ok(parse_management_servers(&answer))
    }

    pub fn prepare_for_maintenance(&self, id: &str) -> Result<(), String> {
        self.call("prepareForMaintenance", &[("managementserverid", id)]).map(|_| ())
    }

    pub fn cancel_maintenance(&self, id: &str) -> Result<(), String> {
        self.call("cancelMaintenance", &[("managementserverid", id), ("rebalance", "true")]).map(|_| ())
    }
}

pub fn parse_management_servers(answer: &serde_json::Value) -> Vec<ManagementServer> {
    let field = |server: &serde_json::Value, name: &str| server[name].as_str().unwrap_or_default().to_string();
    answer["listmanagementserversresponse"]["managementserver"]
        .as_array()
        .map(|servers| {
            servers
                .iter()
                .map(|server| ManagementServer {
                    id: field(server, "id"),
                    state: field(server, "state"),
                    service_ip: field(server, "serviceip"),
                })
                .collect()
        })
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_sorts_by_name_and_encodes_values() {
        let q = query(&[("response", "json"), ("command", "listZones"), ("name", "a b/c")]);
        assert_eq!(q, "command=listZones&name=a%20b%2Fc&response=json");
    }

    #[test]
    fn signature_is_hmac_sha1_over_the_lower_cased_query() {
        let key = format!("k{}", std::process::id());
        let mut mac = Hmac::<Sha1>::new_from_slice(key.as_bytes()).unwrap();
        mac.update(b"command=listzones&response=json");
        let expected = base64::engine::general_purpose::STANDARD.encode(mac.finalize().into_bytes());
        assert_eq!(signature("command=listZones&response=json", &key), expected);
    }

    #[test]
    fn management_servers_are_read_from_the_list_answer() {
        let answer = serde_json::json!({"listmanagementserversresponse": {"count": 2, "managementserver": [
            {"id": "a", "state": "Up", "serviceip": "10.0.0.10"},
            {"id": "b", "state": "Maintenance", "serviceip": "10.0.0.11"}
        ]}});
        let servers = parse_management_servers(&answer);
        assert_eq!(servers.len(), 2);
        assert_eq!((servers[1].id.as_str(), servers[1].state.as_str(), servers[1].service_ip.as_str()), ("b", "Maintenance", "10.0.0.11"));
        assert!(parse_management_servers(&serde_json::json!({})).is_empty());
    }
}
