#devtoolset 4 doesn't have all tools 2 has so add 2 first to get 4-versions of common tools
#source /opt/rh/devtoolset-2/enable
#source /opt/rh/devtoolset-4/enable
#FLEXLM setup
#source /tools/flexlm/flexlm.sh

#RISCV setup
#export RISCV=/tools/projects/colins/eagleX/install
#export PATH=$PATH:$RISCV/bin

#Setup VCS
#export PATH=/tools/synopsys/vcs/N-2017.12-SP1-1/bin:$PATH
#export VCS_HOME=/tools/synopsys/vcs/N-2017.12-SP1-1/
#export VCS_64=1

#Setup Hammer
export HAMMER_HOME=`readlink -f hammer`
source hammer/sourceme.sh

#Setup SDCard VIP
#export DENALI=/tools/cadence/VIPCAT/VIPCAT1130045/tools.lnx86/denali_64bit
#export PATH=${DENALI}/bin:$PATH
#export LD_LIBRARY_PATH=${DENALI}/lib:${LD_LIBRARY_PATH}

# Calibre
#export HAMMER_INTECH22_PLUGIN_PATH=`readlink -f hammer-intech22-plugin`
#source hammer-intech22-plugin/bashrc.sh

# Virtuoso
#export TSMCHOME=/tools/tstech16/CLN16FFC/TSMCHOME
#export TSMCPDK_OS_INSTALL_PATH=/tools/tstech16/CLN16FFC/PDK
#export CDS_INST_DIR=/tools/cadence/ICADV/ICADV123
#export CDSHOME=$CDS_INST_DIR
#export PATH=${CDS_INST_DIR}/tools/bin:${PATH}
#export PATH=${CDS_INST_DIR}/tools/dfII/bin:${PATH}
#export PATH=${CDS_INST_DIR}/tools/plot/bin:${PATH}

