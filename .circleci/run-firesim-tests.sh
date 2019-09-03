#!/bin/bash

# turn echo on and error on earliest command
set -ex

# get remote exec variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/defaults.sh

export PATH=$LOCAL_VERILATOR_DIR/install/bin:$PATH
export FIRESIM_ENV_SOURCED=1

SIMULATION_ARGS="${mapping[$1]}"

run_test_suite () {
    $SIMULATION_ARGS
    TRIPLET=${DESIGN}-${TARGET_CONFIG}-${PLATFORM_CONFIG}
    OUTPUT_DIR=$LOCAL_FIRESIM_DIR/output/f1/${TRIPLET}
    GENERATED_DIR=$LOCAL_FIRESIM_DIR/generated-src/f1/${TRIPLET}
    ln -fs $RISCV/riscv64-unknown-elf/share/riscv-tests/isa/rv64ud-v-fcvt ${OUTPUT_DIR}/rv64ud-v-fcvt
    cd $LOCAL_FIRESIM_DIR/generated-src/f1/${TRIPLET}
    ./V${DESIGN} ${OUTPUT_DIR}/rv64ud-v-fcvt $(cat runtime.conf) +nic-loopback0 +linklatency0=6405 +blkdev-in-mem0=128 +max-cycles=100000 
    #make -C $LOCAL_FIRESIM_DIR $SIMULATION_ARGS run-${1}-tests-fast
}

run_test_suite regression
run_test_suite nic
run_test_suite fast-blkdev
