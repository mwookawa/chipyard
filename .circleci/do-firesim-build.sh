#!/bin/bash

# create the different verilator builds
# argument is the make command string

# turn echo on and error on earliest command
set -ex

# get shared variables
SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
source $SCRIPT_DIR/defaults.sh

# call clean on exit
trap clean EXIT

cd $LOCAL_CHIPYARD_DIR
./scripts/init-submodules-no-riscv-tools.sh
./scripts/firesim-setup.sh --submodules-only

# set stricthostkeychecking to no (must happen before rsync)
run "echo \"Ping $SERVER\""

clean

# copy over riscv/esp-tools, verilator, and chipyard to remote
run "mkdir -p $REMOTE_CHIPYARD_DIR"
run "mkdir -p $REMOTE_VERILATOR_DIR"
copy $LOCAL_CHIPYARD_DIR/ $SERVER:$REMOTE_CHIPYARD_DIR

TOOLS_DIR=$REMOTE_RISCV_DIR
LD_LIB_DIR=$REMOTE_RISCV_DIR/lib
VERILATOR_BIN_DIR=$REMOTE_VERILATOR_DIR/install/bin

if [ $1 = "hwacha" ]; then
    TOOLS_DIR=$REMOTE_ESP_DIR
    LD_LIB_DIR=$REMOTE_ESP_DIR/lib
    run "mkdir -p $REMOTE_ESP_DIR"
    copy $LOCAL_ESP_DIR/ $SERVER:$REMOTE_ESP_DIR
else
    run "mkdir -p $REMOTE_RISCV_DIR"
    copy $LOCAL_RISCV_DIR/ $SERVER:$REMOTE_RISCV_DIR
fi

run "make -C $REMOTE_FIRESIM_DIR clean"
run "export RISCV=\"$TOOLS_DIR\"; export LD_LIBRARY_PATH=\"$LD_LIB_DIR\"; export PATH=\"$VERILATOR_BIN_DIR:\$PATH\"; make -C $REMOTE_FIRESIM_DIR JAVA_ARGS=\"-Xmx8G -Xss8M\" ${mapping[$1]} verilator"
