// Package bonjour advertises the receiver over mDNS as _shotsync._tcp,
// carrying non-sensitive metadata in TXT records. Android discovers it
// through NsdManager and prefers it over configured remote endpoints.
package bonjour

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

const (
	serviceType  = "_shotsync._tcp"
	mdnsGroup    = "224.0.0.251:5353"
	mdnsGroupV6  = "[ff02::fb]:5353"
	maxPacketLen = 9000
)

// Advertiser runs a minimal mDNS responder for the ShotSync service.
type Advertiser struct {
	instance string
	host     string
	port     int
	txt      map[string]string
	log      *slog.Logger

	mu     sync.Mutex
	conn4  *net.UDPConn
	conn6  *net.UDPConn
	cancel context.CancelFunc
}

// New creates an advertiser. instance is the service instance name
// (device_name is used; collisions are resolved by suffixing).
func New(deviceName, networkHost string, port int, txt map[string]string, log *slog.Logger) *Advertiser {
	instance := sanitizeInstance(deviceName)
	if instance == "" {
		instance = "ShotSync"
	}
	return &Advertiser{
		instance: instance,
		host:     sanitizeHost(networkHost) + ".local.",
		port:     port,
		txt:      txt,
		log:      log,
	}
}

// Start begins announcing until ctx is cancelled.
func (a *Advertiser) Start(ctx context.Context) error {
	conn4, err := net.ListenMulticastUDP("udp4", nil, &net.UDPAddr{IP: net.ParseIP("224.0.0.251"), Port: 5353})
	if err == nil {
		a.conn4 = conn4
	} else {
		a.log.Warn("mdns ipv4 unavailable", "err", err)
	}
	if conn6, err := net.ListenMulticastUDP("udp6", nil, &net.UDPAddr{IP: net.ParseIP("ff02::fb"), Port: 5353}); err == nil {
		a.conn6 = conn6
	}

	ctx, cancel := context.WithCancel(ctx)
	a.cancel = cancel

	go a.loop(ctx)
	if err := a.announce(); err != nil {
		a.log.Warn("initial mdns announce failed", "err", err)
	}
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := a.announce(); err != nil {
					a.log.Warn("mdns announce failed", "err", err)
				}
			}
		}
	}()
	a.log.Info("mdns advertised", "service", serviceType, "instance", a.instance, "port", a.port)
	return nil
}

// Stop terminates the responder and sends a goodbye.
func (a *Advertiser) Stop() {
	if a.cancel != nil {
		a.cancel()
	}
	if a.conn4 != nil {
		_ = a.conn4.Close()
	}
	if a.conn6 != nil {
		_ = a.conn6.Close()
	}
}

func (a *Advertiser) loop(ctx context.Context) {
	buf := make([]byte, maxPacketLen)
	var wg sync.WaitGroup
	read := func(c *net.UDPConn) {
		defer wg.Done()
		for {
			if c == nil {
				return
			}
			_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
			n, addr, err := c.ReadFromUDP(buf)
			if err != nil {
				select {
				case <-ctx.Done():
					return
				default:
				}
				if ne, ok := err.(net.Error); ok && ne.Timeout() {
					continue
				}
				return
			}
			if err := a.handleQuery(buf[:n], c, addr); err != nil {
				a.log.Debug("mdns query ignored", "err", err)
			}
		}
	}
	if a.conn4 != nil {
		wg.Add(1)
		go read(a.conn4)
	}
	if a.conn6 != nil {
		wg.Add(1)
		go read(a.conn6)
	}
	wg.Wait()
}

// announce sends an unsolicited response advertising the service.
func (a *Advertiser) announce() error {
	resp := a.buildResponse(nil)
	return a.send(resp)
}

// handleQuery responds to PTR queries for our service type and
// ANY/SRV/TXT queries for our instance.
func (a *Advertiser) handleQuery(packet []byte, conn *net.UDPConn, from *net.UDPAddr) error {
	var msg dnsmessage.Message
	if err := msg.Unpack(packet); err != nil {
		return err
	}
	if msg.Header.Response || msg.Header.OpCode != 0 {
		return fmt.Errorf("not a query")
	}
	if !a.wants(msg.Questions) {
		return fmt.Errorf("unrelated query")
	}
	resp := a.buildResponse(&msg)
	return a.sendTo(resp, conn, from)
}

func (a *Advertiser) wants(questions []dnsmessage.Question) bool {
	for _, q := range questions {
		if q.Type == dnsmessage.TypePTR && strings.HasSuffix(q.Name.String(), serviceType+".local.") {
			return true
		}
		if (q.Type == dnsmessage.TypeALL || q.Type == dnsmessage.TypeSRV || q.Type == dnsmessage.TypeTXT) &&
			strings.EqualFold(q.Name.String(), a.instance+"."+serviceType+".local.") {
			return true
		}
		if (q.Type == dnsmessage.TypeALL || q.Type == dnsmessage.TypeA || q.Type == dnsmessage.TypeAAAA) &&
			strings.EqualFold(q.Name.String(), a.host) {
			return true
		}
	}
	return false
}

func (a *Advertiser) buildResponse(query *dnsmessage.Message) []byte {
	instanceFQDN := a.instance + "." + serviceType + ".local."
	var answers []dnsmessage.Resource

	if query == nil {
		// Unsolicited announcement: PTR + SRV + TXT + A/AAAA.
		answers = append(answers,
			ptrRecord(serviceType+".local.", instanceFQDN, 120),
			srvRecord(instanceFQDN, a.host, uint16(a.port), 120),
			txtRecord(instanceFQDN, a.txt, 120))
		answers = append(answers, addressRecords(a.host, 120)...)
	} else {
		for _, q := range query.Questions {
			switch {
			case q.Type == dnsmessage.TypePTR && strings.HasSuffix(q.Name.String(), serviceType+".local."):
				answers = append(answers, ptrRecord(q.Name.String(), instanceFQDN, 120))
			case q.Type == dnsmessage.TypeSRV && strings.EqualFold(q.Name.String(), instanceFQDN):
				answers = append(answers, srvRecord(instanceFQDN, a.host, uint16(a.port), 120))
			case q.Type == dnsmessage.TypeTXT && strings.EqualFold(q.Name.String(), instanceFQDN):
				answers = append(answers, txtRecord(instanceFQDN, a.txt, 120))
			case (q.Type == dnsmessage.TypeA || q.Type == dnsmessage.TypeAAAA || q.Type == dnsmessage.TypeALL) &&
				strings.EqualFold(q.Name.String(), a.host):
				answers = append(answers, addressRecords(a.host, 120)...)
			}
		}
	}
	if len(answers) == 0 {
		return nil
	}

	resp := dnsmessage.Message{
		Header: dnsmessage.Header{
			ID:               0,
			Response:         true,
			Authoritative:    true,
			RecursionDesired: query != nil && query.Header.RecursionDesired,
			RCode:            dnsmessage.RCodeSuccess,
		},
	}
	if query != nil {
		resp.Header.ID = query.Header.ID
		resp.Questions = query.Questions
	}
	resp.Answers = answers
	out, err := resp.Pack()
	if err != nil {
		return nil
	}
	return out
}

func (a *Advertiser) send(packet []byte) error {
	if packet == nil {
		return nil
	}
	var errs []error
	if a.conn4 != nil {
		if _, err := a.conn4.WriteToUDP(packet, &net.UDPAddr{IP: net.ParseIP("224.0.0.251"), Port: 5353}); err != nil {
			errs = append(errs, err)
		}
	}
	if a.conn6 != nil {
		if _, err := a.conn6.WriteToUDP(packet, &net.UDPAddr{IP: net.ParseIP("ff02::fb"), Port: 5353}); err != nil {
			errs = append(errs, err)
		}
	}
	if len(errs) > 0 {
		return errs[0]
	}
	return nil
}

func (a *Advertiser) sendTo(packet []byte, conn *net.UDPConn, to *net.UDPAddr) error {
	if packet == nil || conn == nil {
		return nil
	}
	_, err := conn.WriteToUDP(packet, to)
	return err
}

// --- record builders ---

func ptrRecord(name, target string, ttl uint32) dnsmessage.Resource {
	n, _ := dnsmessage.NewName(name)
	t, _ := dnsmessage.NewName(target)
	return dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{Name: n, Type: dnsmessage.TypePTR, Class: dnsmessage.ClassINET, TTL: ttl},
		Body:   &dnsmessage.PTRResource{PTR: t},
	}
}

func srvRecord(name, target string, port uint16, ttl uint32) dnsmessage.Resource {
	n, _ := dnsmessage.NewName(name)
	t, _ := dnsmessage.NewName(target)
	return dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{Name: n, Type: dnsmessage.TypeSRV, Class: dnsmessage.ClassINET, TTL: ttl},
		Body: &dnsmessage.SRVResource{
			Target: t, Port: port, Priority: 0, Weight: 0,
		},
	}
}

func txtRecord(name string, txt map[string]string, ttl uint32) dnsmessage.Resource {
	n, _ := dnsmessage.NewName(name)
	keys := make([]string, 0, len(txt))
	for k := range txt {
		keys = append(keys, k)
	}
	// deterministic order
	for i := 0; i < len(keys); i++ {
		for j := i + 1; j < len(keys); j++ {
			if keys[j] < keys[i] {
				keys[i], keys[j] = keys[j], keys[i]
			}
		}
	}
	var txts []string
	for _, k := range keys {
		txts = append(txts, k+"="+txt[k])
	}
	return dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{Name: n, Type: dnsmessage.TypeTXT, Class: dnsmessage.ClassINET, TTL: ttl},
		Body:   &dnsmessage.TXTResource{TXT: txts},
	}
}

func addressRecords(host string, ttl uint32) []dnsmessage.Resource {
	n, _ := dnsmessage.NewName(host)
	var out []dnsmessage.Resource
	for _, ip := range localIPv4s() {
		out = append(out, dnsmessage.Resource{
			Header: dnsmessage.ResourceHeader{Name: n, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: ttl},
			Body:   &dnsmessage.AResource{A: [4]byte(ip.To4())},
		})
	}
	for _, ip := range localIPv6s() {
		out = append(out, dnsmessage.Resource{
			Header: dnsmessage.ResourceHeader{Name: n, Type: dnsmessage.TypeAAAA, Class: dnsmessage.ClassINET, TTL: ttl},
			Body:   &dnsmessage.AAAAResource{AAAA: [16]byte(ip.To16())},
		})
	}
	return out
}

func localIPv4s() []net.IP {
	var ips []net.IP
	byName, _ := net.Interfaces()
	for _, i := range byName {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && !ipn.IP.IsLinkLocalUnicast() {
				ips = append(ips, ipn.IP)
			}
		}
	}
	return ips
}

func localIPv6s() []net.IP {
	var ips []net.IP
	byName, _ := net.Interfaces()
	for _, i := range byName {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() == nil && !ipn.IP.IsLinkLocalUnicast() && !ipn.IP.IsLoopback() {
				ips = append(ips, ipn.IP)
			}
		}
	}
	return ips
}

func sanitizeHost(host string) string {
	host = strings.TrimSpace(strings.TrimSuffix(host, ".local"))
	host = strings.ReplaceAll(host, " ", "-")
	if host == "" {
		return "shotsync-receiver"
	}
	return host
}

func sanitizeInstance(name string) string {
	name = strings.TrimSpace(name)
	name = strings.ReplaceAll(name, ".", "-")
	if len(name) > 63 {
		name = name[:63]
	}
	return name
}

var _ = strconv.Itoa
var _ = mdnsGroup
var _ = mdnsGroupV6
