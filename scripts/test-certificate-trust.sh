#!/usr/bin/env bash
set -euo pipefail
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
touch "$work/index.txt"
printf '1000\n' > "$work/serial"
printf '1000\n' > "$work/crlnumber"
cat > "$work/ca.cnf" <<EOF
[ ca ]
default_ca = local
[ local ]
database = $work/index.txt
new_certs_dir = $work
certificate = $work/ca.pem
private_key = $work/ca.key
serial = $work/serial
crlnumber = $work/crlnumber
default_md = sha256
default_days = 2
default_crl_days = 1
policy = names
x509_extensions = leaf
[ names ]
commonName = supplied
[ req ]
distinguished_name = dn
[ dn ]
[ root ]
basicConstraints = critical,CA:true
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
[ leaf ]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
EOF
openssl req -new -x509 -newkey rsa:2048 -nodes -days 3 -sha256 -subj '/CN=FiskAI synthetic root' -config "$work/ca.cnf" -extensions root -keyout "$work/ca.key" -out "$work/ca.pem" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -subj '/CN=FiskAI synthetic leaf' -keyout "$work/leaf.key" -out "$work/leaf.csr" 2>/dev/null
openssl ca -batch -notext -config "$work/ca.cnf" -in "$work/leaf.csr" -out "$work/leaf.pem" 2>/dev/null
openssl ca -gencrl -config "$work/ca.cnf" -out "$work/valid.crl" 2>/dev/null
openssl ca -config "$work/ca.cnf" -revoke "$work/leaf.pem" 2>/dev/null
openssl ca -gencrl -config "$work/ca.cnf" -out "$work/revoked.crl" 2>/dev/null
php server/tests/CertificateTrustTest.php "$work/leaf.pem" "$work/ca.pem" "$work/valid.crl" "$work/revoked.crl"
