#ifndef __SIMIF_H
#define __SIMIF_H

#include <cassert>
#include <cstring>
#include <sstream>
#include <vector>
#include <map>
#include <queue>
#include <sys/time.h>
#include "biguint.h"
// #include "sample.h"
#include <iostream>

static inline uint64_t timestamp() {
  struct timeval tv;
  gettimeofday(&tv,NULL);
  return 1000000L * tv.tv_sec + tv.tv_usec;
}

typedef std::map< std::string, size_t > idmap_t;
typedef std::map< std::string, size_t >::const_iterator idmap_it_t;

class simif_t
{
  public:
    simif_t(std::vector<std::string> args, std::string prefix, bool _log = false); 
    virtual ~simif_t();

  private:
    void read_map(std::string filename);
    // void read_chain(std::string filename);
    virtual void load_mem(std::string filename);

    // simulation information
    const std::string prefix;
    const bool log; 
    bool ok;
    uint64_t t;
    uint64_t fail_t;
    size_t trace_count;
    size_t trace_len;
    time_t seed; 

    // maps 
    idmap_t in_map;
    idmap_t out_map;
    idmap_t in_tr_map;
    idmap_t out_tr_map;
    uint32_t poke_map[POKE_SIZE];
    uint32_t peek_map[PEEK_SIZE];

    // sample information
    size_t sample_num;
    size_t last_sample_id;
    // sample_t* last_sample;

    // profile information    
    bool profile;
    size_t sample_count;
    uint64_t sample_time;
    uint64_t sim_start_time;
    std::vector<std::string> hargs;
    std::vector<std::string> targs;

    std::map<size_t, size_t> in_chunks;
    std::map<size_t, size_t> out_chunks;

  protected:
    // channel communication
    virtual void poke_channel(size_t addr, uint32_t data) = 0;
    virtual uint32_t peek_channel(size_t addr) = 0;

    // Simulation APIs
    inline size_t get_in_id(std::string path) {
      assert(in_map.find(path) != in_map.end()); 
      return in_map[path];
    }

    inline size_t get_out_id(std::string path) {
      assert(out_map.find(path) != out_map.end()); 
      return out_map[path];
    }

    inline void poke(size_t id, uint32_t value) { 
      poke_map[id] = value; 
    }

    inline uint32_t peek(size_t id) {
      return peek_map[id]; 
    }

    inline void poke(std::string path, uint32_t value) {
      if (log) fprintf(stdout, "* POKE %s <- %x *\n", path.c_str(), value);
      poke(get_in_id(path), value);
    }

    inline uint32_t peek(std::string path) {
      uint32_t value = peek(get_out_id(path));
      if (log) fprintf(stdout, "* PEEK %s <- %x *\n", path.c_str(), value);
      return value;
    }

    inline void poke(size_t id, biguint_t& data) {
      for (size_t off = 0 ; off < in_chunks[id] ; off++) {
        poke_map[id+off] = data[off];
      }
    }

    inline void peek(size_t id, biguint_t& data) {
      data = biguint_t(peek_map+id, out_chunks[id]);
    }

    inline void poke(std::string path, biguint_t &value) {
      if (log) fprintf(stdout, "* POKE %s <- %s *\n", path.c_str(), value.str().c_str());
      poke(get_in_id(path), value);
    }

    inline void peek(std::string path, biguint_t &value) {
      peek(get_out_id(path), value); 
      if (log) fprintf(stdout, "* PEEK %s <- %s *\n", path.c_str(), value.str().c_str());
    }

    inline bool expect(std::string path, uint32_t expected) {
      uint32_t value = peek(path);
      bool pass = value == expected;
      std::ostringstream oss;
      if (log) oss << "EXPECT " << path << " " << value << " == " << expected;
      return expect(pass, oss.str().c_str());
    }

    inline bool expect(std::string path, biguint_t& expected) {
      biguint_t value;
      peek(get_out_id(path), value);
      bool pass = value == expected;
      std::ostringstream oss;
      if (log) oss << "EXPECT " << path << " " << value << " == " << expected;
      return expect(pass, oss.str().c_str());
    }

    bool expect(bool pass, const char *s);
    void step(size_t n);
    inline biguint_t read_mem(size_t addr) {
      poke_channel(MEM_AR_ADDR, addr);
      uint32_t d[MEM_DATA_CHUNK];
      for (size_t off = 0 ; off < MEM_DATA_CHUNK; off++) {
        d[off] = peek_channel(MEM_R_ADDR+off);
      }
      return biguint_t(d, MEM_DATA_CHUNK);
    }
    inline void write_mem(size_t addr, biguint_t& data) {
      poke_channel(MEM_AW_ADDR, addr);
      for (size_t off = 0 ; off < MEM_DATA_CHUNK ; off++) {
        poke_channel(MEM_W_ADDR+off, data[off]);
      }
    }
    // sample_t* trace_ports(sample_t* s);
    // sample_t* read_snapshot();
    
    void init();
    void finish();
    inline uint64_t cycles() { return t; }
    inline void set_latency(size_t cycles) { 
      poke_channel(LATENCY_ADDR, cycles);
    }
    inline void set_tracelen(size_t len) { 
      trace_len = len;
      poke_channel(TRACELEN_ADDR, len);
    }
    inline size_t get_tracelen() { return trace_len; }
    uint64_t rand_next(uint64_t limit) { return rand() % limit; } 
};

#endif // __SIMIF_H
