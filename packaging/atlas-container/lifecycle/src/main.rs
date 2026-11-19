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

//! Probes and drain for one CloudStack management server in a pod.
//!
//! `/readyz` answers 200 only while this server should take new traffic: no
//! drain in progress, the local portal answers within 4 s and this server's
//! row in `listManagementServers` reads `Up`, all held without a break for the
//! warm-up. `/livez` fails once the server has given no HTTP answer at all
//! for a minute after its start-up grace. `/prestop` is the pod's preStop
//! hook: it stops readiness, then, when a peer is `Up`, hands this server's
//! jobs and agents to it with `prepareForMaintenance` and waits for
//! `Maintenance`. A server that starts in `Maintenance` is returned with
//! `cancelMaintenance`, rebalancing the agents.

mod api;

use std::env;
use std::fs;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use api::Api;

const CHECK_INTERVAL: Duration = Duration::from_secs(2);
const PORTAL_TIMEOUT: Duration = Duration::from_secs(4);
const LIVENESS_SILENCE: Duration = Duration::from_secs(60);

struct Config {
    api_url: String,
    portal_url: String,
    service_ip: String,
    listen: String,
    warm_up: Duration,
    start_grace: Duration,
    drain_timeout: Duration,
}

#[derive(Default)]
struct State {
    draining: bool,
    passing_since: Option<Instant>,
    last_http_answer: Option<Instant>,
    own_id: Option<String>,
    own_state: Option<String>,
    peer_up: bool,
    cancel_sent: bool,
}

fn env_or(name: &str, default: &str) -> String {
    env::var(name).unwrap_or_else(|_| default.to_string())
}

fn env_secs(name: &str, default: u64) -> Duration {
    Duration::from_secs(env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default))
}

fn read_secret(name: &str) -> String {
    let path = env::var(name).unwrap_or_else(|_| panic!("{name} must name a file"));
    fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read the file {name} names: {e}"))
        .trim()
        .to_string()
}

/// Whether the server answers HTTP on its portal, and whether that answer is a 200 within the timeout.
fn portal(agent: &ureq::Agent, url: &str) -> (bool, bool) {
    match agent.get(url).timeout(PORTAL_TIMEOUT).call() {
        Ok(response) => (true, response.status() == 200),
        Err(ureq::Error::Status(_, _)) => (true, false),
        Err(_) => (false, false),
    }
}

fn check(config: &Config, api: &Api, agent: &ureq::Agent, state: &Mutex<State>) {
    let (answered, portal_ok) = portal(agent, &config.portal_url);
    let servers = api.management_servers().ok();
    let mut s = state.lock().unwrap();
    if answered {
        s.last_http_answer = Some(Instant::now());
    }
    if let Some(servers) = &servers {
        let own = servers.iter().find(|m| m.service_ip == config.service_ip);
        s.own_id = own.map(|m| m.id.clone());
        s.own_state = own.map(|m| m.state.clone());
        s.peer_up = servers.iter().any(|m| m.service_ip != config.service_ip && m.state == "Up");
    }
    if s.own_state.as_deref() == Some("Maintenance") && !s.draining && !s.cancel_sent {
        if let Some(id) = s.own_id.clone() {
            drop(s);
            let result = api.cancel_maintenance(&id);
            eprintln!("started in Maintenance; cancelMaintenance: {}", if result.is_ok() { "sent" } else { "failed" });
            s = state.lock().unwrap();
            s.cancel_sent = result.is_ok();
        }
    }
    let passing = !s.draining && portal_ok && s.own_state.as_deref() == Some("Up");
    if passing {
        s.passing_since.get_or_insert_with(Instant::now);
    } else {
        s.passing_since = None;
    }
}

fn readiness(config: &Config, s: &State) -> (u16, &'static str) {
    if s.draining {
        return (503, "draining");
    }
    match s.passing_since {
        Some(since) if since.elapsed() >= config.warm_up => (200, "healthy"),
        Some(_) => (503, "warming"),
        None => (503, "down"),
    }
}

fn liveness(config: &Config, started: Instant, s: &State) -> (u16, &'static str) {
    if started.elapsed() < config.start_grace {
        return (200, "starting");
    }
    match s.last_http_answer {
        Some(at) if at.elapsed() < LIVENESS_SILENCE => (200, "alive"),
        _ => (503, "silent"),
    }
}

fn drain(config: &Config, api: &Api, state: &Mutex<State>) -> &'static str {
    let (own_id, peer_up) = {
        let mut s = state.lock().unwrap();
        s.draining = true;
        s.passing_since = None;
        (s.own_id.clone(), s.peer_up)
    };
    let Some(id) = own_id else {
        return "no own row: stopped without a drain";
    };
    if !peer_up {
        return "no peer Up: stopped without a drain";
    }
    if api.prepare_for_maintenance(&id).is_err() {
        return "prepareForMaintenance failed: stopped without a drain";
    }
    let deadline = Instant::now() + config.drain_timeout;
    while Instant::now() < deadline {
        if let Ok(servers) = api.management_servers() {
            if servers.iter().any(|m| m.id == id && m.state == "Maintenance") {
                return "drained";
            }
        }
        thread::sleep(Duration::from_secs(5));
    }
    "drain timed out"
}

fn main() {
    let config = Arc::new(Config {
        api_url: env_or("CLOUDSTACK_API_URL", "http://127.0.0.1:8080/client/api"),
        portal_url: env_or("CLOUDSTACK_PORTAL_URL", "http://127.0.0.1:8080/client/"),
        service_ip: env::var("POD_IP").expect("POD_IP must name the address of this server"),
        listen: env_or("LIFECYCLE_LISTEN", "0.0.0.0:8098"),
        warm_up: env_secs("WARM_UP_SECONDS", 120),
        start_grace: env_secs("START_GRACE_SECONDS", 900),
        drain_timeout: env_secs("DRAIN_TIMEOUT_SECONDS", 540),
    });
    let api = Arc::new(Api::new(
        &config.api_url,
        read_secret("CLOUDSTACK_API_KEY_FILE"),
        read_secret("CLOUDSTACK_SECRET_KEY_FILE"),
    ));
    let state = Arc::new(Mutex::new(State::default()));
    let started = Instant::now();

    {
        let (config, api, state) = (config.clone(), api.clone(), state.clone());
        thread::spawn(move || {
            let agent = ureq::AgentBuilder::new().build();
            loop {
                check(&config, &api, &agent, &state);
                thread::sleep(CHECK_INTERVAL);
            }
        });
    }

    let server = tiny_http::Server::http(&config.listen).expect("cannot listen");
    for request in server.incoming_requests() {
        let (config, api, state) = (config.clone(), api.clone(), state.clone());
        thread::spawn(move || {
            let (status, body) = match request.url() {
                "/readyz" => readiness(&config, &state.lock().unwrap()),
                "/livez" => liveness(&config, started, &state.lock().unwrap()),
                "/prestop" => (200, drain(&config, &api, &state)),
                _ => (404, "not found"),
            };
            if request.url() == "/prestop" {
                eprintln!("preStop: {body}");
            }
            let _ = request.respond(tiny_http::Response::from_string(body).with_status_code(status));
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> Config {
        Config {
            api_url: String::new(),
            portal_url: String::new(),
            service_ip: "10.0.0.10".into(),
            listen: String::new(),
            warm_up: Duration::from_secs(120),
            start_grace: Duration::from_secs(900),
            drain_timeout: Duration::from_secs(540),
        }
    }

    #[test]
    fn readiness_needs_the_warm_up() {
        let mut s = State { passing_since: Some(Instant::now()), ..Default::default() };
        assert_eq!(readiness(&config(), &s), (503, "warming"));
        s.passing_since = Instant::now().checked_sub(Duration::from_secs(121));
        assert_eq!(readiness(&config(), &s), (200, "healthy"));
    }

    #[test]
    fn a_drain_wins_over_a_passing_check() {
        let s = State {
            draining: true,
            passing_since: Instant::now().checked_sub(Duration::from_secs(600)),
            ..Default::default()
        };
        assert_eq!(readiness(&config(), &s), (503, "draining"));
    }

    #[test]
    fn not_passing_is_down() {
        assert_eq!(readiness(&config(), &State::default()), (503, "down"));
    }

    #[test]
    fn liveness_waits_out_the_start_grace() {
        let s = State::default();
        assert_eq!(liveness(&config(), Instant::now(), &s), (200, "starting"));
        let long_ago = Instant::now().checked_sub(Duration::from_secs(1000)).unwrap();
        assert_eq!(liveness(&config(), long_ago, &s), (503, "silent"));
        let answered = State { last_http_answer: Some(Instant::now()), ..Default::default() };
        assert_eq!(liveness(&config(), long_ago, &answered), (200, "alive"));
    }
}
