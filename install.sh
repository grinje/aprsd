#!/bin/bash

INSTALLDIR="/usr/local/polaric-aprsd"

echo 'Creating directories...'
mkdir logs

echo 'Adding to system...'
ln -s $INSTALLDIR/polaric-aprsd /etc/init.d/polaric-aprsd
ln -s /etc/init.d/polaric-aprsd /etc/rc0.d/K20polaric
ln -s /etc/init.d/polaric-aprsd /etc/rc1.d/K20polaric
ln -s /etc/init.d/polaric-aprsd /etc/rc2.d/S20polaric
ln -s /etc/init.d/polaric-aprsd /etc/rc3.d/S20polaric
ln -s /etc/init.d/polaric-aprsd /etc/rc4.d/S20polaric
ln -s /etc/init.d/polaric-aprsd /etc/rc5.d/S20polaric
ln -s /etc/init.d/polaric-aprsd /etc/rc6.d/K20polaric
mv logrotate /etc/logrotate.d/polaric-aprsd

echo 'Polaric-APRSD is now installed on your system.'

if [ ! -f $INSTALLDIR/server.ini ] ; then
   cp $INSTALLDIR/server.ini.example $INSTALLDIR/server.ini
   echo 'Please edit server.ini'
fi


