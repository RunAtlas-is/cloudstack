# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import unittest
from unittest import mock

from cs import CsHelper
from cs.CsNetfilter import CsNetfilter, CsNetfilters
import configure
import merge

PUBLIC_IP = "203.0.113.10"

# The ingress firewall rules of one public IP as iptables-save prints them.
SAVED = [
    "*mangle",
    ":PREROUTING ACCEPT [0:0]",
    ":FIREWALL_%s - [0:0]" % PUBLIC_IP,
    "-A FIREWALL_%s -s 198.51.100.0/24 -p tcp -m tcp --dport 22 -j RETURN" % PUBLIC_IP,
    "-A FIREWALL_%s -s 192.0.2.0/24 -p tcp -m tcp --dport 22 -j RETURN" % PUBLIC_IP,
    "-A FIREWALL_%s -s 192.0.2.0/24 -p udp -m udp --dport 8000:8080 -j RETURN" % PUBLIC_IP,
    "-A FIREWALL_%s -s 198.51.100.0/24 -p icmp -m icmp --icmp-type any -j RETURN" % PUBLIC_IP,
    "COMMIT",
]


class FwStub(object):
    def __init__(self):
        self.rules = []

    def get_fw(self):
        return self.rules


def ingress_rule(protocol, cidrs, ports=None):
    rule = {
        'id': 1,
        'traffic_type': 'Ingress',
        'src_ip': PUBLIC_IP,
        'protocol': protocol,
        'source_cidr_list': cidrs,
    }
    if ports:
        rule['src_port_range'] = list(ports)
    if protocol == 'icmp':
        rule['icmp_type'] = -1
        rule['icmp_code'] = -1
    return rule


def generated_rules():
    """ The rules the router builds from the same firewall rules. """
    fw = FwStub()
    for rule in [ingress_rule('tcp', ['198.51.100.0/24', '192.0.2.0/24'], (22, 22)),
                 ingress_rule('udp', ['192.0.2.0/24'], (8000, 8080)),
                 ingress_rule('icmp', ['198.51.100.0/24'])]:
        configure.CsAcl.AclIP(rule, fw).create()
    return fw.get_fw()


class TestCsNetfilter(unittest.TestCase):

    def setUp(self):
        merge.DataBag.DPATH = "."

    def test_init(self):
        csnetfilter = CsNetfilter()
        self.assertTrue(csnetfilter is not None)

    def test_generated_ingress_rules_parse_as_iptables_save_prints_them(self):
        saved = [line for line in SAVED if line.startswith('-A')]
        for generated, printed in zip(generated_rules(), saved):
            a = CsNetfilter()
            a.parse(generated[2])
            b = CsNetfilter()
            b.parse(printed)
            self.assertEqual(a.get_rule(), b.get_rule())

    def test_unchanged_ingress_rules_run_no_iptables_command(self):
        commands = []

        def execute(command):
            commands.append(command)
            return SAVED if command == "iptables-save" else []

        with mock.patch.object(CsHelper, 'execute', side_effect=execute), \
                mock.patch.object(CsHelper, 'execute2') as execute2, \
                mock.patch.object(CsNetfilters, 'del_standard'):
            netfilters = CsNetfilters()
            netfilters.compare(generated_rules())

        execute2.assert_not_called()
        self.assertEqual(["iptables-save"], commands)

    def test_a_new_ingress_rule_runs_one_insert(self):
        commands = []

        def execute(command):
            commands.append(command)
            return SAVED if command == "iptables-save" else []

        fw = generated_rules()
        fw.append(["mangle", "front",
                   " -A FIREWALL_%s -s 203.0.113.0/24  -p tcp  -m tcp   --dport 443  -j RETURN" % PUBLIC_IP])
        with mock.patch.object(CsHelper, 'execute', side_effect=execute), \
                mock.patch.object(CsHelper, 'execute2') as execute2, \
                mock.patch.object(CsNetfilters, 'del_standard'):
            execute2.return_value.returncode = 0
            netfilters = CsNetfilters()
            netfilters.compare(fw)

        self.assertEqual(1, execute2.call_count)
        self.assertIn("-I FIREWALL_%s -s 203.0.113.0/24" % PUBLIC_IP, execute2.call_args[0][0])
        self.assertEqual(["iptables-save"], commands)


if __name__ == '__main__':
    unittest.main()
